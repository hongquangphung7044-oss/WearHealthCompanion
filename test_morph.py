"""
B 方案 + 形态验证：
1. 用低百分位阈值提候选
2. 形态验证：R 波必须是局部尖窄峰（不能是宽缓波）
3. 不应期 + 精修
验证：运动后检出 ≈53，静息 ≈27，且短 RR(<400ms)少
"""
import re, math

def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]

def compute_envelope(ecg, sr=500):
    baseline = [0.0] * len(ecg)
    half_win = sr // 2
    for i in range(len(ecg)):
        lo, hi = max(0, i-half_win), min(len(ecg), i+half_win)
        baseline[i] = sum(ecg[lo:hi]) / (hi-lo)
    hp = [ecg[i] - baseline[i] for i in range(len(ecg))]
    grad = [0.0] * len(ecg)
    for i in range(len(ecg)):
        prev, nxt = hp[max(0,i-1)], hp[min(len(ecg)-1,i+1)]
        grad[i] = abs(nxt - prev) / 2.0
    sw = int(sr * 0.150)
    env = [0.0] * len(ecg)
    for i in range(len(ecg)):
        lo, hi = max(0, i-sw//2), min(len(ecg), i+sw//2+1)
        env[i] = sum(grad[lo:hi]) / (hi-lo)
    return env, hp

def percentile(sorted_list, p):
    if not sorted_list: return 0.0
    idx = min(int(len(sorted_list) * p), len(sorted_list)-1)
    return sorted_list[idx]

def detect_with_morphology(ecg, env, hp, sr, pct, floor=3.0, seg_sec=4):
    """
    百分位阈值 + 形态验证：
    - 候选: env > thr
    - 形态验证: 候选点处 |high_passed| 必须显著高于左右 50ms 处（尖峰特征）
      R 波是尖窄峰，50ms 内应明显回落；缓波/基线漂移不会
    - 不应期 200ms
    - 精修
    """
    seg_len = sr * seg_sec
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg_sorted = sorted(env[ss:se])
        seg_thr = max(percentile(seg_sorted, pct), floor)
        for i in range(ss, se):
            thresholds[i] = seg_thr

    refractory = sr // 5  # 200ms
    check_range = sr // 50  # ±10ms
    flank = sr // 20  # 50ms 侧翼（R 波应在 50ms 内回落）
    r_peaks = []
    last_peak = -refractory * 2

    for i in range(len(env)):
        if env[i] < thresholds[i]: continue
        # 局部最大检查
        lo, hi = max(0, i-check_range), min(len(env), i+check_range+1)
        is_max = True
        for j in range(lo, hi):
            if j != i and env[j] >= env[i]:
                if j < i: is_max = False; break
        if not is_max: continue
        if (i - last_peak) < refractory: continue

        # 形态验证：候选点 |hp| 必须高于两侧 50ms 处 |hp|（尖峰 vs 缓波）
        left_idx = max(0, i - flank)
        right_idx = min(len(hp)-1, i + flank)
        cand_val = abs(hp[i])
        left_val = abs(hp[left_idx])
        right_val = abs(hp[right_idx])
        # R 波振幅应至少是 50ms 侧翼的 1.5 倍（QRS 持续 80-100ms，50ms 处应已回落 1/3 以上）
        if cand_val < left_val * 1.3 or cand_val < right_val * 1.3:
            continue  # 缓波，不是 R 波

        # 精修
        r_lo, r_hi = max(0, i-sr//40), min(len(hp), i+sr//40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检
    if len(r_peaks) >= 5:
        extra = []
        for k in range(1, len(r_peaks)):
            rr = r_peaks[k] - r_peaks[k-1]
            rs, re_ = max(0, k-4), min(len(r_peaks), k+4)
            recent = [r_peaks[m]-r_peaks[m-1] for m in range(rs+1, re_) if m != k]
            if len(recent) < 3: continue
            avg = sum(recent)/len(recent)
            if avg < 1.0: continue
            if rr > avg * 1.66:
                mid = (r_peaks[k-1] + r_peaks[k]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss, se = r_peaks[k-1]+refractory, r_peaks[k]-refractory
                if se <= ss: continue
                best_b, best_bv = -1, back_thr
                for j in range(ss, se+1):
                    if j < 0 or j >= len(env): continue
                    if env[j] > best_bv:
                        bl, bh = max(0,j-check_range), min(len(env),j+check_range+1)
                        is_m = True
                        for m in range(bl, bh):
                            if m != j and env[m] > env[j]: is_m = False; break
                        if is_m:
                            # 形态验证
                            lf = max(0, j-flank); rf = min(len(hp)-1, j+flank)
                            if abs(hp[j]) < abs(hp[lf])*1.3 or abs(hp[j]) < abs(hp[rf])*1.3:
                                continue
                            best_bv = env[j]; best_b = j
                if best_b >= 0: extra.append(best_b)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks

def compute_hr(rr_ms_list):
    if not rr_ms_list: return 0
    rr_sorted = sorted(rr_ms_list)
    median = rr_sorted[len(rr_sorted)//2]
    filtered = [r for r in rr_ms_list if abs(r-median)/median <= 0.30]
    if not filtered: return 0
    return int(60000 / (sum(filtered)/len(filtered)))

files = {
    "运动后(107bpm)": ("samples/运动后.txt", 107, 53),
    "静息(54bpm)": ("samples/静息.txt", 54, 27),
    "静息换右手(59bpm)": ("samples/心电api静息换右手.txt", 59, 30),
    "活动后旧(72bpm)": ("samples/心电api活动后.txt", 72, 36),
    "静息旧(59bpm)": ("samples/心电api静息.txt", 59, 30),
}

print("=" * 90)
print("B 方案 + 形态验证（侧翼 1.3 倍）")
print("=" * 90)
print(f"{'文件':<22} {'百分位':<8} {'检出':<6} {'期望':<6} {'心率':<6} {'HV':<6} {'短RR':<6} {'正常RR':<8}")
print("-" * 90)

for pct in [0.85, 0.88, 0.90, 0.92]:
    for label, (path, hv_hr, expected_r) in files.items():
        ecg = load_ecg(path)
        env, hp = compute_envelope(ecg)
        r = detect_with_morphology(ecg, env, hp, 500, pct)
        rr = [(r[i]-r[i-1])*1000/500 for i in range(1, len(r))]
        hr = compute_hr(rr)
        short = sum(1 for x in rr if x < 400)
        normal = sum(1 for x in rr if 400 <= x <= 800)
        print(f"{label:<22} {pct:<8.2f} {len(r):<6} {expected_r:<6} {hr:<6} {hv_hr:<6} {short:<6} {normal:<8}")
    print()
