"""
B 方案验证：用百分位数代替 mean+2std
目标：运动后检出 ≈53 个 R 波（HV 107bpm）
同时验证：静息数据不被破坏（应保持 ~27 个，HV 54bpm）
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
    """简单百分位：p in [0,1]"""
    if not sorted_list: return 0.0
    idx = int(len(sorted_list) * p)
    idx = min(idx, len(sorted_list)-1)
    return sorted_list[idx]

def detect_with_percentile(ecg, env, hp, sr, pct, floor=3.0, use_segment=True, seg_sec=4):
    """用百分位阈值检测 R 波"""
    refractory = sr // 5  # 200ms
    check_range = sr // 50
    r_peaks = []
    last_peak = -refractory * 2

    if use_segment:
        seg_len = sr * seg_sec
        thresholds = [0.0] * len(env)
        for ss in range(0, len(env), seg_len):
            se = min(len(env), ss + seg_len)
            seg_sorted = sorted(env[ss:se])
            seg_thr = percentile(seg_sorted, pct) * 1.0  # 直接用百分位
            seg_thr = max(seg_thr, floor)
            for i in range(ss, se):
                thresholds[i] = seg_thr
    else:
        env_sorted = sorted(env)
        thr = max(percentile(env_sorted, pct), floor)
        thresholds = [thr] * len(env)

    for i in range(len(env)):
        if env[i] < thresholds[i]: continue
        lo, hi = max(0, i-check_range), min(len(env), i+check_range+1)
        is_max = True
        for j in range(lo, hi):
            if j != i and env[j] >= env[i]:
                if j < i: is_max = False; break
        if not is_max: continue
        if (i - last_peak) < refractory: continue
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

# 测试两个文件
files = {
    "运动后(107bpm)": ("samples/运动后.txt", 107, 53),
    "静息(54bpm)": ("samples/静息.txt", 54, 27),
    "静息换右手(59bpm)": ("samples/心电api静息换右手.txt", 59, 30),
}

print("=" * 80)
print("B 方案验证：分段百分位阈值")
print("=" * 80)
print(f"{'文件':<22} {'百分位':<8} {'检出':<6} {'期望':<6} {'心率':<6} {'HV心率':<8} {'偏差':<8}")
print("-" * 80)

# 试不同百分位
for pct in [0.70, 0.75, 0.80, 0.85, 0.90]:
    for label, (path, hv_hr, expected_r) in files.items():
        ecg = load_ecg(path)
        env, hp = compute_envelope(ecg)
        r = detect_with_percentile(ecg, env, hp, 500, pct)
        rr = [(r[i]-r[i-1])*1000/500 for i in range(1, len(r))]
        hr = compute_hr(rr)
        dev = abs(hr - hv_hr) if hr > 0 else 999
        print(f"{label:<22} {pct:<8.2f} {len(r):<6} {expected_r:<6} {hr:<6} {hv_hr:<8} {dev:<8}")
    print()

# 最佳百分位下的详细分析
print("=" * 80)
print("详细：选定百分位 0.80")
print("=" * 80)
for label, (path, hv_hr, expected_r) in files.items():
    ecg = load_ecg(path)
    env, hp = compute_envelope(ecg)
    r = detect_with_percentile(ecg, env, hp, 500, 0.80)
    rr = [(r[i]-r[i-1])*1000/500 for i in range(1, len(r))]
    hr = compute_hr(rr)
    short = sum(1 for x in rr if x < 400)
    normal = sum(1 for x in rr if 400 <= x <= 800)
    long_ = sum(1 for x in rr if x > 800)
    print(f"\n{label}: 检出 {len(r)}/{expected_r}, 心率 {hr} vs HV {hv_hr}")
    print(f"  RR 分类: 短(<400)={short}, 正常(400-800)={normal}, 长(>800)={long_}")
    if rr:
        print(f"  RR 前10: {[int(x) for x in rr[:10]]}")
