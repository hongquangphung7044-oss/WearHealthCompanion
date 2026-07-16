"""
Debug: 干净 70bpm 信号下，min 方案为什么检出 30 而非 35（旧 mean+2std）。
排查阈值是否正确取 mean+2std，以及形态验证是否误杀真实 R 波。
"""
import math
import random
from verify_red import compute_envelope, detect_mean_std, percentile


def synthetic_ecg(duration_sec, r_peak_times, r_amp_mv=1.0, noise_level=0.01, seed=42,
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


def detect_with_debug(ecg, env, hp, sr=500, pct=0.92, floor=3.0, seg_sec=4, flank_ratio=1.1):
    """min 方案 + 详细 debug 输出"""
    seg_len = sr * seg_sec
    thresholds = [0.0] * len(env)
    print("\n=== 分段阈值 ===")
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
        seg_max = max(seg)
        chosen = "mean+2std" if thr_meanstd <= thr_pct else "percentile"
        print(f"  段[{ss//sr}s-{se//sr}s] mean+2std={thr_meanstd:.2f} pct0.92={thr_pct:.2f} "
              f"min={thr:.2f} (取{chosen}) segMax={seg_max:.2f}")
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5
    check_range = sr // 50
    flank = sr // 20
    r_peaks = []
    last_peak = -refractory * 2
    morph_fail = 0
    morph_fail_details = []

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

        # 形态验证
        left_idx = max(0, i - flank)
        right_idx = min(len(hp) - 1, i + flank)
        cand_val = abs(hp[i])
        left_val = abs(hp[left_idx])
        right_val = abs(hp[right_idx])
        if cand_val < left_val * flank_ratio or cand_val < right_val * flank_ratio:
            morph_fail += 1
            t = i / sr
            morph_fail_details.append(
                f"  t={t:.3f}s i={i} env={env[i]:.2f} |hp|={cand_val:.1f} "
                f"左|hp|={left_val:.1f}(x1.1={left_val*1.1:.1f}) 右|hp|={right_val:.1f}(x1.1={right_val*1.1:.1f})"
            )
            continue
        # 精修
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    print(f"\n=== 主检测 ===")
    print(f"  检出 {len(r_peaks)} 个 R 波")
    print(f"  形态验证失败 {morph_fail} 个候选")
    if morph_fail_details:
        print("  形态失败详情（前10个）:")
        for d in morph_fail_details[:10]:
            print(d)
    return r_peaks


# 场景1: 干净 70bpm
r_times = [i * 0.857 for i in range(35)]
ecg = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg)

print("=" * 80)
print("场景1: 干净 70bpm 信号（期望 33-37）")
print("=" * 80)

r_old = detect_mean_std(ecg, env, hp)
print(f"\n旧 mean+2std: 检出 {len(r_old)} 个")

r_new = detect_with_debug(ecg, env, hp, flank_ratio=1.1)
print(f"\n新 min+形态(1.1): 检出 {len(r_new)} 个")

# 对比：去掉形态验证
r_new_no_morph = detect_with_debug(ecg, env, hp, flank_ratio=0.0)
print(f"\n新 min+无形态(0.0): 检出 {len(r_new_no_morph)} 个")
