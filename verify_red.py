"""
验证 RED 假设：合成运动后信号（107bpm + 肌电干扰），对比当前 mean+2std vs 百分位 0.92 + 形态验证。

真实运动后漏检根因（analyze_motion3.py）：
- 肌电干扰让 envelope 右偏长尾，mean+2std 被长尾拉高
- R 波 envelope 峰仅 0.85~1.11 倍阈值 → 漏检 60%
- 百分位 0.92 对右偏长尾稳健

合成信号要复现"右偏长尾 envelope"：
- 高斯噪声是正态分布，mean+2std 最优不会漏检
- 需要叠加"脉冲式"高频噪声（偶发大尖峰）模拟肌电突发，让 envelope 长尾
- 同时降低 R 波振幅（运动后电极接触变化，R 波振幅降低）

目标：mean+2std 检出 <40（漏检），百分位 0.92 + 形态验证检出 >=40
"""
import math
import random


def synthetic_ecg_motion(duration_sec, r_peak_times, r_amp_mv=0.5, base_noise=0.15,
                         muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=200):
    """
    合成运动后 ECG：R 波 + uniform 噪声（匹配 Kotlin syntheticEcg）+ 肌电突发（高斯尖峰）
    返回 mV*1000 整数列表，500Hz

    匹配 Kotlin syntheticEcg 的噪声模型：(rng.nextDouble()-0.5)*2*noiseLevel = uniform[-noiseLevel,+noiseLevel]
    肌电突发用 rng.nextGaussian()*burst_amp（Kotlin 测试中也用 nextGaussian）
    """
    sr = 500
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma = 0.01  # R 波高斯宽度 10ms（匹配 Kotlin 默认 rSigmaSec=0.01）
    sigma_sq2 = 2 * sigma * sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        # 基础噪声：uniform（匹配 Kotlin syntheticEcg）
        v += (rng.random() - 0.5) * 2 * base_noise
        # 肌电干扰：uniform（模拟肌电底，手动叠加）
        v += (rng.random() - 0.5) * 2 * muscle_noise
        # 肌电突发：偶发大尖峰（让 envelope 右偏长尾，Kotlin 用 nextGaussian）
        if rng.random() < burst_prob:
            v += rng.gauss(0, 1) * burst_amp
        data.append(int(v * 1000))
    return data


def compute_envelope(ecg, sr=500):
    """复现 EcgFeatureExtractor 预处理：去基线(1s) + |梯度|/2 + 150ms boxcar"""
    baseline = [0.0] * len(ecg)
    half_win = sr // 2
    for i in range(len(ecg)):
        lo, hi = max(0, i - half_win), min(len(ecg), i + half_win)
        baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
    hp = [ecg[i] - baseline[i] for i in range(len(ecg))]
    grad = [0.0] * len(ecg)
    for i in range(len(ecg)):
        prev, nxt = hp[max(0, i - 1)], hp[min(len(ecg) - 1, i + 1)]
        grad[i] = abs(nxt - prev) / 2.0
    sw = int(sr * 0.150)
    env = [0.0] * len(ecg)
    for i in range(len(ecg)):
        lo, hi = max(0, i - sw // 2), min(len(ecg), i + sw // 2 + 1)
        env[i] = sum(grad[lo:hi]) / (hi - lo)
    return env, hp


def percentile(sorted_list, p):
    if not sorted_list:
        return 0.0
    idx = min(int(len(sorted_list) * p), len(sorted_list) - 1)
    return sorted_list[idx]


def detect_mean_std(ecg, env, hp, sr=500, floor=3.0, seg_sec=4):
    """当前实现：4秒分段 mean+2std 阈值，无形态验证"""
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
        thr = mean + std * 2.0
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
        # 精修
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


def detect_percentile_morph(ecg, env, hp, sr=500, pct=0.92, floor=3.0, seg_sec=4, flank_ratio=1.1):
    """新方案：4秒分段 min(百分位0.92, mean+2std) 阈值 + 形态验证（侧翼 flank_ratio 倍）

    阈值选择 min(百分位0.92, mean+2std) 的依据：
    - 干净信号 envelope 是双峰分布（低噪声 + R 波），百分位0.92 落在 R 波区间下边缘（阈值偏高），
      mean+2std 落在两峰之间（阈值更低），min 取 mean+2std → R 波全检出
    - 运动后 envelope 右偏长尾（少量肌电突发极高值），mean+2std 被长尾拉高（>百分位0.92），
      min 取百分位0.92 → 不被长尾拉高，R 波多检出
    - Python 验证（debug_threshold.py）：干净信号百分位0.92=27.9 > mean+2std=26.4，min 取 26.4
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

    refractory = sr // 5
    check_range = sr // 50
    flank = sr // 20  # 50ms 侧翼
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
        # 形态验证：候选点 |hp| 必须高于两侧 50ms 处 |hp| 的 flank_ratio 倍
        left_idx = max(0, i - flank)
        right_idx = min(len(hp) - 1, i + flank)
        cand_val = abs(hp[i])
        left_val = abs(hp[left_idx])
        right_val = abs(hp[right_idx])
        if cand_val < left_val * flank_ratio or cand_val < right_val * flank_ratio:
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

    # 回溯补检（带形态验证）
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
                            # 形态验证
                            lf = max(0, j - flank)
                            rf = min(len(hp) - 1, j + flank)
                            if abs(hp[j]) < abs(hp[lf]) * flank_ratio or abs(hp[j]) < abs(hp[rf]) * flank_ratio:
                                continue
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


def run_scenario(label, r_amp, base_noise, muscle_noise, burst_prob, burst_amp, seed):
    """跑一个合成场景，对比两种算法"""
    # 107bpm, RR=560ms, 30秒约 53 个 R 波
    r_times = []
    t = 0.5
    while t < 30.0:
        r_times.append(t)
        t += 0.56
    expected = len(r_times)

    ecg = synthetic_ecg_motion(30.0, r_times, r_amp_mv=r_amp, base_noise=base_noise,
                               muscle_noise=muscle_noise, burst_prob=burst_prob,
                               burst_amp=burst_amp, seed=seed)
    env, hp = compute_envelope(ecg)

    r_old = detect_mean_std(ecg, env, hp)
    r_new_11 = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.1)
    r_new_10 = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.0)

    # 短 RR 统计（误检指标）
    def short_rr(r_peaks):
        rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / 500 for i in range(1, len(r_peaks))]
        return sum(1 for x in rr if x < 400)

    print(f"\n[{label}] R波振幅={r_amp}mV 基础噪声={base_noise} 肌电底={muscle_noise} "
          f"突发概率={burst_prob} 突发幅度={burst_amp} seed={seed}")
    print(f"  期望 R 波数: {expected}")
    print(f"  当前 mean+2std:        检出 {len(r_old):3d}  短RR<400ms={short_rr(r_old)}")
    print(f"  百分位0.92+形态(1.1):  检出 {len(r_new_11):3d}  短RR<400ms={short_rr(r_new_11)}")
    print(f"  百分位0.92+形态(1.0):  检出 {len(r_new_10):3d}  短RR<400ms={short_rr(r_new_10)}")
    return len(r_old), len(r_new_11), len(r_new_10)


if __name__ == "__main__":
    print("=" * 90)
    print("RED 假设验证：合成运动后信号（107bpm + 强肌电干扰）")
    print("目标：mean+2std 检出 <35（漏检），百分位 0.92 + 形态验证检出 >=35")
    print("参数对齐 Kotlin 测试：r_amp=0.5 base_noise=0.15 muscle=0.4 burst3%@1.0mV")
    print("=" * 90)

    # 强肌电场景（对齐 Kotlin 测试参数），多 seed 验证稳健性
    for seed in [200, 201, 202, 300, 400, 500]:
        run_scenario(f"强肌电 seed={seed}", r_amp=0.5, base_noise=0.15, muscle_noise=0.4,
                     burst_prob=0.03, burst_amp=1.0, seed=seed)
