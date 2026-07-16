"""
扩展测试：用 ±50ms 精修窗口验证所有依赖 R 峰的测试场景。
"""
import math
import random
from java_random import JavaRandom, kotlin_synthetic_ecg
from verify_red import compute_envelope


def detect_kstd_refine(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4,
                        refine_half_ms=25):
    seg_len = sr * seg_len if False else sr * seg_sec
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
        r_lo, r_hi = max(0, i - refine_samples), min(len(hp), i + refine_samples)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

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


def compute_hr(r_peaks, sr=500, age_years=0):
    if len(r_peaks) < 2:
        return (0, 0, 0)
    rr = [int((r_peaks[i] - r_peaks[i-1]) * 1000.0 / sr) for i in range(1, len(r_peaks))]
    rr = [x for x in rr if 300 <= x <= 2500]
    if len(rr) < 3:
        return (0, 0, 0)
    mean = sum(rr) / len(rr)
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20] if mean > 0 else rr
    if not filtered:
        filtered = rr
    if len(filtered) < 3:
        return (0, 0, 0)
    med_rr = median(filtered)
    if med_rr <= 0:
        return (0, 0, 0)
    med_hr = int(60000.0 / med_rr)
    if not (40 <= med_hr <= 200):
        return (0, 0, 0)
    var_pct = 0.20
    lower = med_rr * (1 - var_pct)
    upper = med_rr * (1 + var_pct)
    normal = [x for x in filtered if lower <= x <= upper]
    rr_range = normal if len(normal) >= 3 else filtered
    min_rr = min(rr_range)
    max_rr = max(rr_range)
    return (med_hr, int(60000.0 / max_rr), int(60000.0 / min_rr))


sr = 500

print("=" * 90)
print("扩展测试：±50ms 精修窗口对依赖 R 峰的测试场景的影响")
print("=" * 90)
print()

# ===== 测试1: detectsRegularRPeaksAndHeartRate =====
print("--- 测试1: detectsRegularRPeaksAndHeartRate ---")
r_times = [i * 0.857 for i in range(35)]
ecg = kotlin_synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    avg, mn, mx = compute_hr(r, sr)
    ok = (33 <= len(r) <= 37) and (65 <= avg <= 75) and (mx - mn <= 10)
    print(f"  ±{refine}ms: R={len(r)} avgHR={avg} minHR={mn} maxHR={mx} range={mx-mn} {'✅' if ok else '❌'}")

# ===== 测试2: detectsRPeaksInMotionArtifactSignal =====
print()
print("--- 测试2: detectsRPeaksInMotionArtifactSignal (6 seed) ---")
r_times_motion = []
t = 0.5
while t < 30.0:
    r_times_motion.append(t)
    t += 0.56
for refine in [25, 50]:
    counts = []
    for seed in [200, 201, 202, 300, 400, 500]:
        ecg_m = []
        rng = JavaRandom(seed)
        sigma = 0.01
        sigma_sq2 = 2 * sigma * sigma
        for i in range(int(30.0 * sr)):
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
        r = detect_kstd_refine(ecg_m, env_m, hp_m, sr=sr, k=1.7, refine_half_ms=refine)
        counts.append(len(r))
    ok = min(counts) >= 33
    print(f"  ±{refine}ms: {counts} min={min(counts)} {'✅' if ok else '❌'}")

# ===== 测试3: flatSegmentWithMicroNoiseDoesNotProduceFalsePeaks =====
# 30 秒，只有 2 个 R 波（1s 和 2s），其余平坦微噪声
print()
print("--- 测试3: flatSegmentWithMicroNoiseDoesNotProduceFalsePeaks ---")
# 用 Python random（Kotlin 用 java.util.Random，模式相同），seed=42
rng_py = random.Random(42)
r_times_flat = [1.0, 2.0]
ecg_flat = []
sigma = 0.01
sigma_sq2 = 2 * sigma * sigma
for i in range(int(30.0 * sr)):
    t = i / sr
    v = 0.0
    for rT in r_times_flat:
        dt = t - rT
        v += 1.0 * math.exp(-(dt * dt) / sigma_sq2)
    v += (rng_py.random() - 0.5) * 2 * 0.02
    ecg_flat.append(int(v * 1000))
env, hp = compute_envelope(ecg_flat, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg_flat, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    ok = len(r) <= 4
    print(f"  ±{refine}ms: R={len(r)} (期望<=4) {'✅' if ok else '❌'}")

# ===== 测试4: detectsRPeaksInCleanSegmentDespiteNoiseInOtherSegment =====
# 前 15 秒干净 70bpm + 后 15 秒大振幅噪声
print()
print("--- 测试4: detectsRPeaksInCleanSegmentDespiteNoiseInOtherSegment ---")
r_times_clean = [i * 0.857 for i in range(18) if i * 0.857 < 15.0]
ecg_clean = kotlin_synthetic_ecg(15.0, r_times_clean, r_amp_mv=1.0, noise_level=0.02, seed=100)
rng_noise = JavaRandom(20260716)
ecg_noise = [int(rng_noise.nextGaussian() * 3.0 * 1000) for _ in range(15 * sr)]
ecg_combined = ecg_clean + ecg_noise
env, hp = compute_envelope(ecg_combined, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg_combined, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    ok = len(r) >= 14
    print(f"  ±{refine}ms: R={len(r)} (期望>=14) {'✅' if ok else '❌'}")

# ===== 测试5: detectsRPeaksInModerateNoiseRealisticSignal =====
# 30 秒 70bpm + 0.15mV 噪声
print()
print("--- 测试5: detectsRPeaksInModerateNoiseRealisticSignal ---")
r_times_mod = [i * 0.857 for i in range(35)]
ecg_mod = kotlin_synthetic_ecg(30.0, r_times_mod, r_amp_mv=1.0, noise_level=0.15, seed=100)
env, hp = compute_envelope(ecg_mod, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg_mod, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    ok = len(r) >= 20
    print(f"  ±{refine}ms: R={len(r)} (期望>=20) {'✅' if ok else '❌'}")

# ===== 测试6: noiseSegmentRPeaksDoNotPolluteStats =====
# 0-10s 干净 + 10-20s 噪声段 + 20-30s 干净
print()
print("--- 测试6: noiseSegmentRPeaksDoNotPolluteStats ---")
r_times_a = [i * 0.857 for i in range(12)]
r_times_b = [20.0 + i * 0.857 for i in range(12)]
r_times_mix = r_times_a + r_times_b
ecg_mix = kotlin_synthetic_ecg(30.0, r_times_mix, r_amp_mv=1.0, noise_level=0.02, seed=42)
# 替换 10-20s 为低振幅高斯噪声
rng_noise2 = JavaRandom(999)
ecg_mix_list = list(ecg_mix)
for i in range(10 * sr, 20 * sr):
    ecg_mix_list[i] = int(rng_noise2.nextGaussian() * 30)
ecg_mix = ecg_mix_list
env, hp = compute_envelope(ecg_mix, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg_mix, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    # 模拟噪声段掩码剔除（10-20s 的 R 波剔除）
    effective_r = [p for p in r if not (10 * sr <= p < 20 * sr)]
    rr_count = max(0, len(effective_r) - 1)
    ok = rr_count <= 22
    print(f"  ±{refine}ms: 原始R={len(r)} 有效R={len(effective_r)} RR数={rr_count} (期望<=22) {'✅' if ok else '❌'}")

# ===== 测试7: segmentRPeakCountMatchesInput =====
# 每秒正好 1 个 R 波
print()
print("--- 测试7: segmentRPeakCountMatchesInput ---")
r_times_seg = [i + 0.3 for i in range(30)]
ecg_seg = kotlin_synthetic_ecg(30.0, r_times_seg, r_amp_mv=1.0, noise_level=0.02, seed=42)
env, hp = compute_envelope(ecg_seg, sr)
for refine in [25, 50]:
    r = detect_kstd_refine(ecg_seg, env, hp, sr=sr, k=1.7, refine_half_ms=refine)
    # 统计每秒段（0-1, 1-2, ...）的 R 波数
    seg_counts = [0] * 30
    for p in r:
        sec = p // sr
        if 0 <= sec < 30:
            seg_counts[sec] += 1
    ones = sum(1 for c in seg_counts if c == 1)
    ok = ones >= 25
    print(f"  ±{refine}ms: R={len(r)} 段R=1的数量={ones} (期望>=25) {'✅' if ok else '❌'}")
