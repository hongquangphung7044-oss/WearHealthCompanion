"""
测试 k=2.0 + 降低回溯补检触发阈值（1.66→1.4）对运动后信号的效果。
如果回溯更早触发，可能找回更多漏检 R 波，无需降低 k。
"""
import math
from java_random import JavaRandom, kotlin_synthetic_ecg
from verify_red import compute_envelope


def detect_kstd_backtrack(ecg, env, hp, sr=500, k=2.0, floor=3.0, seg_sec=4,
                          backtrack_trigger=1.66, backtrack_thr_ratio=0.5):
    """mean+k*std 分段阈值 + 可调回溯补检参数"""
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
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检（可调触发阈值和搜索阈值）
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
            if rr > avg * backtrack_trigger:  # 可调触发阈值
                mid = (r_peaks[k_idx - 1] + r_peaks[k_idx]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * backtrack_thr_ratio  # 可调搜索阈值
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


def synthetic_ecg_motion(duration_sec, r_peak_times, r_amp_mv=0.5, base_noise=0.15,
                         muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=200):
    """运动后合成信号（用 JavaRandom 匹配 Kotlin）"""
    sr = 500
    total = int(duration_sec * sr)
    rng = JavaRandom(seed)
    sigma = 0.01
    sigma_sq2 = 2 * sigma * sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        v += (rng.nextDouble() - 0.5) * 2 * base_noise
        v += (rng.nextDouble() - 0.5) * 2 * muscle_noise
        if rng.nextDouble() < burst_prob:
            v += rng.nextGaussian() * burst_amp
        data.append(int(v * 1000))
    return data


# 运动后信号参数
r_times_motion = []
t = 0.5
while t < 30.0:
    r_times_motion.append(t)
    t += 0.56

print("=" * 90)
print("k=2.0 + 不同回溯参数对运动后信号的效果（JavaRandom，匹配 Kotlin）")
print("=" * 90)
print(f"{'k':<5} {'trigger':<9} {'thr_ratio':<11} | {'6 seed R 波数':<30} | {'min':<5}")
print("-" * 90)

for k in [2.0]:
    for trigger in [1.66, 1.5, 1.4, 1.3]:
        for thr_ratio in [0.5, 0.4, 0.35]:
            counts = []
            for seed in [200, 201, 202, 300, 400, 500]:
                ecg = synthetic_ecg_motion(30.0, r_times_motion, r_amp_mv=0.5,
                                           base_noise=0.15, muscle_noise=0.4,
                                           burst_prob=0.03, burst_amp=1.0, seed=seed)
                env, hp = compute_envelope(ecg, 500)
                r = detect_kstd_backtrack(ecg, env, hp, k=k, backtrack_trigger=trigger,
                                          backtrack_thr_ratio=thr_ratio)
                counts.append(len(r))
            min_c = min(counts)
            all_pass = min_c >= 33
            print(f"{k:<5} {trigger:<9} {thr_ratio:<11} | {str(counts):<30} | {min_c:<5} {'✅' if all_pass else '❌'}")
