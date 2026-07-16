"""
调试场景1回归：干净70bpm信号，百分位0.92检出28<33（期望33-37）
对比 mean+2std vs 百分位0.92 的阈值，看哪个更高
"""
import math
import random
from verify_red import compute_envelope, detect_mean_std, detect_percentile_morph


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


r_times = [i * 0.857 for i in range(35)]
ecg = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg)

# 对比每个4秒分段的阈值
sr = 500
seg_len = sr * 4
print("分段阈值对比（干净70bpm信号）：")
print(f"{'段':<6} {'mean+2std':<12} {'百分位0.92':<12} {'env最大值':<12} {'R波数(估)':<10}")
for seg_idx, seg_start in enumerate(range(0, len(env), seg_len)):
    seg_end = min(len(env), seg_start + seg_len)
    seg = env[seg_start:seg_end]
    if not seg:
        continue
    mean = sum(seg) / len(seg)
    var = sum((x - mean) ** 2 for x in seg) / len(seg)
    std = math.sqrt(var)
    thr_old = mean + std * 2.0
    seg_sorted = sorted(seg)
    pct_idx = min(len(seg) - 1, int(len(seg) * 0.92))
    thr_new = seg_sorted[pct_idx]
    env_max = max(seg)
    # 该段内 R 波数（按 r_times）
    seg_r_count = sum(1 for rt in r_times if seg_start / sr <= rt < seg_end / sr)
    print(f"{seg_idx:<6} {thr_old:<12.2f} {thr_new:<12.2f} {env_max:<12.2f} {seg_r_count:<10}")

# 两种算法检出
r_old = detect_mean_std(ecg, env, hp)
r_new = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.1)
print(f"\n旧 mean+2std 检出: {len(r_old)}")
print(f"新 百分位0.92+形态 检出: {len(r_new)}")

# 看新算法漏检的 R 波位置
r_new_set = set(r_new)
print("\nR 波位置 vs 检出（新算法）：")
for rt in r_times:
    expected_idx = int(rt * sr)
    # 找最近的检出
    nearest = min(r_new, key=lambda x: abs(x - expected_idx)) if r_new else -1
    dist_ms = abs(nearest - expected_idx) * 1000 / sr if nearest >= 0 else 999
    status = "✅" if dist_ms < 50 else "❌漏检"
    if dist_ms >= 50:
        # 看该位置的 envelope 和阈值
        seg_start = (expected_idx // seg_len) * seg_len
        seg_end = min(len(env), seg_start + seg_len)
        seg_sorted = sorted(env[seg_start:seg_end])
        pct_idx = min(len(seg) - 1, int(len(seg) * 0.92))
        thr_new = seg_sorted[pct_idx]
        env_at_r = env[expected_idx]
        # 形态验证
        flank = sr // 20
        left_val = abs(hp[max(0, expected_idx - flank)])
        right_val = abs(hp[min(len(hp) - 1, expected_idx + flank)])
        cand_val = abs(hp[expected_idx])
        morph_ok = cand_val >= left_val * 1.1 and cand_val >= right_val * 1.1
        print(f"  t={rt:.3f}s {status} env={env_at_r:.2f} thr={thr_new:.2f} "
              f"|hp|={cand_val:.1f} 左={left_val:.1f} 右={right_val:.1f} 形态={'OK' if morph_ok else 'FAIL'}")
