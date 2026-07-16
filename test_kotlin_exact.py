"""
用 JavaRandom 精确复现 Kotlin syntheticEcg + EcgFeatureExtractor 全流程，
验证不同 k 值在 detectsRegularRPeaksAndHeartRate 测试上的表现。
"""
import math
from java_random import JavaRandom, kotlin_synthetic_ecg
from verify_red import compute_envelope


def detect_kstd(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
    """mean+k*std 分段阈值 + 回溯补检（无形态验证），匹配 Kotlin detectRPeaks"""
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
        thr = max(mean + k * std, floor)
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5  # 200ms
    check_range = sr // 50  # ±10ms
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
        # 精修 ±25ms
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
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
        for k_idx in range(1, len(r_peaks)):
            rr = r_peaks[k_idx] - r_peaks[k_idx - 1]
            rs, re_ = max(0, k_idx - 4), min(len(r_peaks), k_idx + 4)
            recent = [r_peaks[m] - r_peaks[m - 1] for m in range(rs + 1, re_) if m != k_idx]
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (r_peaks[k_idx - 1] + r_peaks[k_idx]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss2, se2 = r_peaks[k_idx - 1] + refractory, r_peaks[k_idx] - refractory
                if se2 <= ss2:
                    continue
                best_b, best_bv = -1, back_thr
                for j in range(ss2, se2 + 1):
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


def compute_rr(r_peaks, sr=500):
    """匹配 Kotlin computeRRIntervals"""
    if len(r_peaks) < 2:
        return []
    rr = []
    for i in range(1, len(r_peaks)):
        rr_ms = int((r_peaks[i] - r_peaks[i - 1]) * 1000.0 / sr)
        if 300 <= rr_ms <= 2500:
            rr.append(rr_ms)
    return rr


def filter_ectopic(rr):
    """匹配 Kotlin filterEctopicBeats"""
    if len(rr) < 5:
        return rr
    mean = sum(rr) / len(rr)
    if mean <= 0:
        return rr
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20]
    return filtered if filtered else rr


def median(lst):
    if not lst:
        return 0.0
    s = sorted(lst)
    return s[len(s) // 2]


def compute_hr_stats(rr, age_years=0):
    """匹配 Kotlin computeHeartRateStats"""
    if len(rr) < 3:
        return (0, 0, 0)
    med_rr = median(rr)
    if med_rr <= 0:
        return (0, 0, 0)
    med_hr = int(60000.0 / med_rr)
    if not (40 <= med_hr <= 200):
        return (0, 0, 0)
    var_pct = 0.20 if age_years <= 0 else max(5.0, min(20.0, 23.2 - 0.35 * age_years)) / 100.0
    lower = med_rr * (1 - var_pct)
    upper = med_rr * (1 + var_pct)
    normal = [x for x in rr if lower <= x <= upper]
    rr_range = normal if len(normal) >= 3 else rr
    min_rr = min(rr_range)
    max_rr = max(rr_range)
    return (med_hr, int(60000.0 / max_rr), int(60000.0 / min_rr))


def run_test(k, label="detectsRegularRPeaksAndHeartRate"):
    """复现 Kotlin 测试"""
    sr = 500
    r_times = [i * 0.857 for i in range(35)]
    ecg = kotlin_synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
    env, hp = compute_envelope(ecg, sr)
    r_peaks = detect_kstd(ecg, env, hp, sr=sr, k=k)

    # 模拟噪声段掩码（clean signal 不会有噪声段，但需要走完整流程）
    rr = compute_rr(r_peaks, sr)
    rr_filtered = filter_ectopic(rr)
    avg_hr, min_hr, max_hr = compute_hr_stats(rr_filtered, age_years=0)

    print(f"\n=== {label} (k={k}) ===")
    print(f"R peaks: {len(r_peaks)} (expect 33-37)")
    print(f"RR intervals: {rr[:10]}... (first 10)")
    if rr:
        print(f"RR min={min(rr)} max={max(rr)} mean={sum(rr)/len(rr):.1f}")
    print(f"After filterEctopicBeats: {len(rr_filtered)} RRs")
    if rr_filtered:
        print(f"  filtered min={min(rr_filtered)} max={max(rr_filtered)}")
    print(f"HR: avg={avg_hr} min={min_hr} max={max_hr} range={max_hr-min_hr}")
    print(f"Assert1 (R count 33-37): {'PASS' if 33 <= len(r_peaks) <= 37 else 'FAIL'}")
    print(f"Assert2 (avg HR 65-75): {'PASS' if 65 <= avg_hr <= 75 else 'FAIL'}")
    print(f"Assert3 (HR range <=10): {'PASS' if max_hr - min_hr <= 10 else 'FAIL'}")
    return len(r_peaks), avg_hr, min_hr, max_hr


# Test different k values
for k in [2.0, 1.9, 1.85, 1.8, 1.7]:
    run_test(k)
