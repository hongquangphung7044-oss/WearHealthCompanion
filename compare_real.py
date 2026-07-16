"""
对比 3 种方案在真实样本上的表现：
1. 旧 mean+2std（无形态）
2. min(pct0.92, mean+2std) + 无形态验证
3. min(pct0.92, mean+2std) + 方案A形态(先精修±50ms再验证)
目标：找出真实样本不回归 + 运动后改善的方案
"""
import re
import math
from verify_red import compute_envelope, detect_mean_std, percentile


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def compute_hr(r_peaks, sr=500):
    if len(r_peaks) < 2:
        return 0
    rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / sr for i in range(1, len(r_peaks))]
    rr = [x for x in rr if 300 <= x <= 2500]
    if len(rr) < 3:
        return 0
    mean = sum(rr) / len(rr)
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20] if mean > 0 else rr
    if not filtered:
        filtered = rr
    if len(filtered) < 3:
        return 0
    rr_sorted = sorted(filtered)
    median = rr_sorted[len(rr_sorted) // 2]
    if median <= 0:
        return 0
    hr = int(60000 / median)
    return hr if 40 <= hr <= 200 else 0


def detect_min_no_morph(ecg, env, hp, sr=500, pct=0.92, floor=3.0, seg_sec=4):
    """min 阈值 + 无形态验证"""
    seg_len = sr * seg_sec
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / len(seg)
        std = math.sqrt(var)
        thr_meanstd = mean + std * 2.0
        seg_sorted = sorted(seg)
        thr_pct = percentile(seg_sorted, pct)
        thr = max(min(thr_pct, thr_meanstd), floor)
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5
    check_range = sr // 50
    r_peaks = []
    last_peak = -refractory * 2

    for i in range(len(env)):
        thr = max(thresholds[i], floor)
        if env[i] < thr:
            continue
        lo, hi = max(0, i - check_range), min(len(env), i + check_range + 1)
        is_max = True
        for j in range(lo, hi):
            if j != i and env[j] >= env[i]:
                if j < i:
                    is_max = False
                    break
        if not is_max:
            continue
        if (i - last_peak) < refractory:
            continue
        # 精修（±25ms，原始范围）
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检（无形态验证）
    if len(r_peaks) >= 5:
        extra = []
        for k in range(1, len(r_peaks)):
            rr = r_peaks[k] - r_peaks[k - 1]
            rs, re_ = max(0, k - 4), min(len(r_peaks), k + 4)
            recent = [r_peaks[m] - r_peaks[m - 1] for m in range(rs + 1, re_) if m != k]
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (r_peaks[k - 1] + r_peaks[k]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss, se = r_peaks[k - 1] + refractory, r_peaks[k] - refractory
                if se <= ss:
                    continue
                best_b, best_bv = -1, back_thr
                for j in range(ss, se + 1):
                    if j < 0 or j >= len(env):
                        continue
                    if env[j] > best_bv:
                        bl, bh = max(0, j - check_range), min(len(env), j + check_range + 1)
                        is_m = True
                        for m in range(bl, bh):
                            if m != j and env[m] > env[j]:
                                is_m = False
                                break
                        if is_m:
                            best_bv = env[j]
                            best_b = j
                if best_b >= 0:
                    r_lo, r_hi = max(0, best_b - sr // 40), min(len(hp), best_b + sr // 40)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


files = {
    "运动后(新)": ("samples/运动后.txt", 107, 53),
    "静息(新)": ("samples/静息.txt", 54, 27),
    "换右手(旧)": ("samples/心电api静息换右手.txt", 59, 30),
    "活动后(旧)": ("samples/心电api活动后.txt", 72, 36),
    "静息(旧)": ("samples/心电api静息.txt", 59, 30),
}

print("=" * 110)
print("3 方案对比：旧 mean+2std | min阈值无形态 | min阈值+方案A形态")
print("=" * 110)
print(f"{'文件':<14} {'HV':<5} {'期望':<5} | {'旧R/HR':<10} | {'min无形态R/HR/短RR':<18} | {'方案A R/HR/短RR':<18}")
print("-" * 110)

for label, (path, hv_hr, expected_r) in files.items():
    ecg = load_ecg(path)
    env, hp = compute_envelope(ecg)

    r_old = detect_mean_std(ecg, env, hp)
    r_min = detect_min_no_morph(ecg, env, hp)

    from verify_red import detect_percentile_morph
    r_a = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.1)

    hr_old = compute_hr(r_old)
    hr_min = compute_hr(r_min)
    hr_a = compute_hr(r_a)

    def short_rr(r):
        rr = [(r[i] - r[i - 1]) * 1000 / 500 for i in range(1, len(r))]
        return sum(1 for x in rr if x < 400)

    print(f"{label:<14} {hv_hr:<5} {expected_r:<5} | {len(r_old):<2}/{hr_old:<3}   | "
          f"{len(r_min):<2}/{hr_min:<3}/{short_rr(r_min):<2}       | "
          f"{len(r_a):<2}/{hr_a:<3}/{short_rr(r_a):<2}")
