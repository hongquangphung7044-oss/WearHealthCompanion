"""
验证原有测试场景在新算法（百分位 0.92 + 形态验证 1.1）下不回归。
匹配 Kotlin syntheticEcg 的信号模型：R 波高斯 + uniform 噪声。
"""
import math
import random
from verify_red import compute_envelope, detect_mean_std, detect_percentile_morph


def synthetic_ecg(duration_sec, r_peak_times, r_amp_mv=1.0, noise_level=0.02, seed=42,
                  r_sigma=0.01):
    """匹配 Kotlin syntheticEcg：R 波高斯 + uniform 噪声，返回 mV*1000 整数"""
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


def count_r(ecg, detector="new"):
    env, hp = compute_envelope(ecg)
    if detector == "old":
        r = detect_mean_std(ecg, env, hp)
    else:
        r = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.1)
    return len(r)


print("=" * 90)
print("原有测试场景回归验证（新算法：百分位 0.92 + 形态验证 1.1）")
print("=" * 90)

# 场景1: detectsRegularRPeaksAndHeartRate
# 30s, 70bpm (RR=0.857s), rAmp=1.0, noise=0.01, 期望 R 波 33-37
r_times = [i * 0.857 for i in range(35)]
ecg1 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
n1_old = count_r(ecg1, "old")
n1_new = count_r(ecg1, "new")
ok1 = 33 <= n1_new <= 37
print(f"[1] 规则70bpm干净信号: 期望33-37 | 旧={n1_old} 新={n1_new} | {'✅' if ok1 else '❌回归'}")

# 场景2: detectsRPeaksInModerateNoiseRealisticSignal
# 30s, 70bpm, rAmp=1.0, noise=0.15, 期望 >=20
ecg2 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.15, seed=100)
n2_old = count_r(ecg2, "old")
n2_new = count_r(ecg2, "new")
ok2 = n2_new >= 20
print(f"[2] 中等噪声70bpm: 期望>=20 | 旧={n2_old} 新={n2_new} | {'✅' if ok2 else '❌回归'}")

# 场景3: flatSegmentWithMicroNoiseDoesNotProduceFalsePeaks
# 30s, R波在1s/2s, rAmp=1.0, noise=0.02, 期望 <=4
ecg3 = synthetic_ecg(30.0, [1.0, 2.0], r_amp_mv=1.0, noise_level=0.02, seed=42)
n3_old = count_r(ecg3, "old")
n3_new = count_r(ecg3, "new")
ok3 = n3_new <= 4
print(f"[3] 平坦段微噪声: 期望<=4 | 旧={n3_old} 新={n3_new} | {'✅' if ok3 else '❌回归'}")

# 场景4: detectsRPeaksInCleanSegmentDespiteNoiseInOtherSegment
# 前15s 干净70bpm + 后15s 大噪声(3.0mV), 期望 >=14
ecg4a = synthetic_ecg(15.0, [i * 0.857 for i in range(18) if i * 0.857 < 15.0],
                      r_amp_mv=1.0, noise_level=0.02, seed=100)
rng4 = random.Random(20260716)
ecg4b = [int(rng4.gauss(0, 1) * 3.0 * 1000) for _ in range(500 * 15)]
ecg4 = ecg4a + ecg4b
n4_old = count_r(ecg4, "old")
n4_new = count_r(ecg4, "new")
ok4 = n4_new >= 14
print(f"[4] 干净段+噪声段: 期望>=14 | 旧={n4_old} 新={n4_new} | {'✅' if ok4 else '❌回归'}")

# 场景5: detectsRPeaksInMotionArtifactSignal (RED 测试场景)
# 30s, 107bpm, rAmp=0.5, noise=0.15 + 肌电干扰, 期望 >=33
r_times5 = []
t = 0.5
while t < 30.0:
    r_times5.append(t)
    t += 0.56
ecg5 = synthetic_ecg(30.0, r_times5, r_amp_mv=0.5, noise_level=0.15, seed=200)
rng5 = random.Random(201)
for i in range(len(ecg5)):
    muscle = (rng5.random() - 0.5) * 2 * 0.4
    burst = rng5.gauss(0, 1) * 1.0 if rng5.random() < 0.03 else 0.0
    ecg5[i] = ecg5[i] + int((muscle + burst) * 1000)
n5_old = count_r(ecg5, "old")
n5_new = count_r(ecg5, "new")
ok5 = n5_new >= 33
print(f"[5] 运动后肌电干扰(RED): 期望>=33 | 旧={n5_old} 新={n5_new} | {'✅GREEN' if ok5 else '❌'} (旧应<33={n5_old < 33})")

print()
all_ok = ok1 and ok2 and ok3 and ok4
red_ok = ok5 and n5_old < 33
print(f"原有测试不回归: {'✅ 全部通过' if all_ok else '❌ 有回归'}")
print(f"RED 假设: 旧算法漏检({n5_old}<33) + 新算法通过({n5_new}>=33): {'✅' if red_ok else '❌'}")
