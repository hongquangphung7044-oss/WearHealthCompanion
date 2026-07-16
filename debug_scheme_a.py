"""
验证方案 A：先精修（±50ms）再形态验证（用精修位置）。
解决 envelope 峰在 R 峰前 43ms 上升沿，形态验证误杀的问题。
"""
import math
import random
from verify_red import compute_envelope, percentile


def synthetic_ecg(duration_sec, r_peak_times, r_amp_mv=1.0, noise_level=0.02, seed=42,
                  r_sigma=0.01):
    sr = 500
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma_sq2 = 2 * r_sigma * r_sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        v += (rng.random() - 0.5) * 2 * noise_level
        data.append(int(v * 1000))
    return data


def synthetic_ecg_motion(duration_sec, r_peak_times, r_amp_mv=0.5, base_noise=0.15,
                         muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=200):
    sr = 500
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma = 0.01
    sigma_sq2 = 2 * sigma * sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        v += (rng.random() - 0.5) * 2 * base_noise
        v += (rng.random() - 0.5) * 2 * muscle_noise
        if rng.random() < burst_prob:
            v += rng.gauss(0, 1) * burst_amp
        data.append(int(v * 1000))
    return data


def detect_scheme_a(ecg, env, hp, sr=500, pct=0.92, floor=3.0, seg_sec=4, flank_ratio=1.1):
    """方案 A：min 阈值 + 先精修(±50ms)再形态验证

    修复：envelope 峰在 R 峰前 40-50ms 上升沿，旧流程"形态验证→精修"误杀上升沿候选。
    新流程"精修→形态验证"：精修范围 ±50ms 覆盖 envelope 峰到 R 峰距离，先找到 |hp| 最大
    位置（R 峰），再用 R 峰位置做形态验证，侧翼 |hp| 低 → 通过。
    """
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

    refractory = sr // 5  # 200ms
    check_range = sr // 50  # ±10ms
    flank = sr // 20  # 50ms 侧翼
    refine_range = sr // 10  # ±50ms 精修范围（覆盖 envelope 峰到 R 峰的 40-50ms 距离）
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

        # 先精修：在 ±50ms 范围找 |hp| 最大位置（R 峰）
        r_lo = max(0, i - refine_range)
        r_hi = min(len(hp), i + refine_range + 1)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j

        # 形态验证：用精修位置 bestIdx，侧翼 bestIdx ± 50ms
        left_idx = max(0, best_idx - flank)
        right_idx = min(len(hp) - 1, best_idx + flank)
        cand_val = abs(hp[best_idx])
        left_val = abs(hp[left_idx])
        right_val = abs(hp[right_idx])
        if cand_val < left_val * flank_ratio or cand_val < right_val * flank_ratio:
            continue

        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检（带精修+形态验证）
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
                            # 先精修
                            r_lo = max(0, j - refine_range)
                            r_hi = min(len(hp), j + refine_range + 1)
                            b_best, b_bestv = j, 0.0
                            for m in range(r_lo, r_hi):
                                if abs(hp[m]) > b_bestv:
                                    b_bestv = abs(hp[m])
                                    b_best = m
                            # 形态验证
                            lf = max(0, b_best - flank)
                            rf = min(len(hp) - 1, b_best + flank)
                            if abs(hp[b_best]) < abs(hp[lf]) * flank_ratio or \
                               abs(hp[b_best]) < abs(hp[rf]) * flank_ratio:
                                continue
                            best_bv = env[j]
                            best_b = b_best
                if best_b >= 0:
                    extra.append(best_b)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def short_rr(r_peaks):
    rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / 500 for i in range(1, len(r_peaks))]
    return sum(1 for x in rr if x < 400)


print("=" * 80)
print("方案 A 验证：min 阈值 + 先精修(±50ms)再形态验证")
print("=" * 80)

# 场景1: 干净 70bpm（期望 33-37）
r_times = [i * 0.857 for i in range(35)]
ecg1 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg1)
r1 = detect_scheme_a(ecg1, env, hp)
ok1 = 33 <= len(r1) <= 37
print(f"[1] 干净70bpm: 检出 {len(r1)} (期望33-37) 短RR={short_rr(r1)} {'✅' if ok1 else '❌'}")

# 场景2: 中等噪声 70bpm（期望 >=20）
ecg2 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.15, seed=100)
env, hp = compute_envelope(ecg2)
r2 = detect_scheme_a(ecg2, env, hp)
ok2 = len(r2) >= 20
print(f"[2] 中等噪声70bpm: 检出 {len(r2)} (期望>=20) 短RR={short_rr(r2)} {'✅' if ok2 else '❌'}")

# 场景4: 干净段+噪声段（期望 >=14）
ecg4a = synthetic_ecg(15.0, [i * 0.857 for i in range(18) if i * 0.857 < 15.0],
                      r_amp_mv=1.0, noise_level=0.02, seed=100)
rng4 = random.Random(20260716)
ecg4b = [int(rng4.gauss(0, 1) * 3.0 * 1000) for _ in range(500 * 15)]
ecg4 = ecg4a + ecg4b
env, hp = compute_envelope(ecg4)
r4 = detect_scheme_a(ecg4, env, hp)
ok4 = len(r4) >= 14
print(f"[4] 干净段+噪声段: 检出 {len(r4)} (期望>=14) 短RR={short_rr(r4)} {'✅' if ok4 else '❌'}")

# 场景5: 运动后肌电干扰（期望 >=33）
r_times5 = []
t = 0.5
while t < 30.0:
    r_times5.append(t)
    t += 0.56
ecg5 = synthetic_ecg_motion(30.0, r_times5, r_amp_mv=0.5, base_noise=0.15,
                            muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=200)
env, hp = compute_envelope(ecg5)
r5 = detect_scheme_a(ecg5, env, hp)
ok5 = len(r5) >= 33
print(f"[5] 运动后肌电(RED): 检出 {len(r5)} (期望>=33) 短RR={short_rr(r5)} {'✅GREEN' if ok5 else '❌'}")

# 多 seed 验证运动后
print("\n运动后多 seed 验证:")
for seed in [200, 201, 202, 300, 400, 500]:
    ecg = synthetic_ecg_motion(30.0, r_times5, r_amp_mv=0.5, base_noise=0.15,
                               muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=seed)
    env, hp = compute_envelope(ecg)
    r = detect_scheme_a(ecg, env, hp)
    print(f"  seed={seed}: 检出 {len(r)} 短RR={short_rr(r)} {'✅' if len(r)>=33 else '❌'}")

print(f"\n汇总: [1]{'✅' if ok1 else '❌'} [2]{'✅' if ok2 else '❌'} "
      f"[4]{'✅' if ok4 else '❌'} [5]{'✅' if ok5 else '❌'}")
