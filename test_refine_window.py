"""
测试不同精修窗口宽度对 HR range 的影响。
根因：envelope 峰在 R 峰上升沿（提前 30-50ms），±25ms 精修窗口够不到 R 峰。
"""
import math
from java_random import JavaRandom, kotlin_synthetic_ecg
from verify_red import compute_envelope


def detect_kstd_refine(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4,
                        refine_half_ms=25):
    """可调精修窗口宽度（ms）"""
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

    refractory = sr // 5
    check_range = sr // 50
    # 精修窗口：从 ±25ms 改为可调
    refine_samples = max(1, int(sr * refine_half_ms / 1000))
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
        # 精修 ±refine_half_ms
        r_lo, r_hi = max(0, i - refine_samples), min(len(hp), i + refine_samples)
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
                    r_lo, r_hi = max(0, best_b - refine_samples), min(len(hp), best_b + refine_samples)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def median(lst):
    if not lst:
        return 0.0
    s = sorted(lst)
    return s[len(s) // 2]


def compute_hr_stats(r_peaks, sr=500, age_years=0):
    if len(r_peaks) < 2:
        return (0, 0, 0, [])
    rr = [int((r_peaks[i] - r_peaks[i-1]) * 1000.0 / sr) for i in range(1, len(r_peaks))]
    rr = [x for x in rr if 300 <= x <= 2500]
    if len(rr) < 3:
        return (0, 0, 0, rr)
    mean = sum(rr) / len(rr)
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20] if mean > 0 else rr
    if not filtered:
        filtered = rr
    if len(filtered) < 3:
        return (0, 0, 0, rr)
    med_rr = median(filtered)
    if med_rr <= 0:
        return (0, 0, 0, rr)
    med_hr = int(60000.0 / med_rr)
    if not (40 <= med_hr <= 200):
        return (0, 0, 0, rr)
    var_pct = 0.20 if age_years <= 0 else max(5.0, min(20.0, 23.2 - 0.35 * age_years)) / 100.0
    lower = med_rr * (1 - var_pct)
    upper = med_rr * (1 + var_pct)
    normal = [x for x in filtered if lower <= x <= upper]
    rr_range = normal if len(normal) >= 3 else filtered
    min_rr = min(rr_range)
    max_rr = max(rr_range)
    return (med_hr, int(60000.0 / max_rr), int(60000.0 / min_rr), rr)


# Test 1: regular rhythm test (detectsRegularRPeaksAndHeartRate)
sr = 500
r_times = [i * 0.857 for i in range(35)]
ecg = kotlin_synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg, sr)

print("=" * 90)
print("测试1: detectsRegularRPeaksAndHeartRate (regular 70bpm)")
print("=" * 90)
print(f"{'k':<5} {'refine_ms':<11} | {'R波数':<7} {'avgHR':<6} {'minHR':<7} {'maxHR':<7} {'range':<7} {'判定':<6}")
print("-" * 90)

for k in [2.0, 1.7]:
    for refine_ms in [25, 35, 50, 75]:
        r = detect_kstd_refine(ecg, env, hp, sr=sr, k=k, refine_half_ms=refine_ms)
        avg, mn, mx, _ = compute_hr_stats(r, sr)
        ok = (33 <= len(r) <= 37) and (65 <= avg <= 75) and (mx - mn <= 10)
        print(f"{k:<5} {refine_ms:<11} | {len(r):<7} {avg:<6} {mn:<7} {mx:<7} {mx-mn:<7} {'✅' if ok else '❌'}")

# Test 2: motion signal test (detectsRPeaksInMotionArtifactSignal)
print()
print("=" * 90)
print("测试2: detectsRPeaksInMotionArtifactSignal (6 seed, 阈值>=33)")
print("=" * 90)
r_times_motion = []
t = 0.5
while t < 30.0:
    r_times_motion.append(t)
    t += 0.56

print(f"{'k':<5} {'refine_ms':<11} | {'6 seed R 波数':<32} {'min':<5} {'判定':<6}")
print("-" * 90)

for k in [2.0, 1.7]:
    for refine_ms in [25, 35, 50, 75]:
        counts = []
        for seed in [200, 201, 202, 300, 400, 500]:
            # 匹配 Kotlin 测试中的运动后信号生成
            ecg_m = []
            sr = 500
            total = int(30.0 * sr)
            rng = JavaRandom(seed)
            sigma = 0.01
            sigma_sq2 = 2 * sigma * sigma
            for i in range(total):
                tt = i / sr
                v = 0.0
                for rT in r_times_motion:
                    dt = tt - rT
                    v += 0.5 * math.exp(-(dt * dt) / sigma_sq2)
                v += (rng.nextDouble() - 0.5) * 2 * 0.15
                v += (rng.nextDouble() - 0.5) * 2 * 0.4
                if rng.nextDouble() < 0.03:
                    v += rng.nextGaussian() * 1.0
                ecg_m.append(int(v * 1000))
            env_m, hp_m = compute_envelope(ecg_m, sr)
            r = detect_kstd_refine(ecg_m, env_m, hp_m, sr=sr, k=k, refine_half_ms=refine_ms)
            counts.append(len(r))
        min_c = min(counts)
        ok = min_c >= 33
        print(f"{k:<5} {refine_ms:<11} | {str(counts):<32} {min_c:<5} {'✅' if ok else '❌'}")
