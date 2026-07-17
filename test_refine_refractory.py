"""
验证"精修突破不应期"假设。

假设：envelope 峰 i 满足 i - lastPeakIdx >= refractory(200ms)，
但精修 bestIdx = i - 50ms，导致相邻精修 R 峰距离 < 200ms 不应期，
产生 156-186ms 的超短 RR。

验证方法：
1. detect_v5_no_backfill 同时返回 envelope 峰位置（精修前）和精修后 R 峰位置
2. 对比精修前后的 RR 序列
3. 找出精修后 RR < 200ms 但精修前 RR >= 200ms 的对，确认假设
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope


def detect_with_env_peaks(ecg, env, hp, sr=500):
    """返回 (envelope峰位置列表, 精修后R峰位置列表)"""
    seg_len = sr * 4
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / len(seg)
        std = math.sqrt(var)
        thr = mean + std * 1.7
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5  # 200ms = 100 samples
    check_range = sr // 50
    env_peaks = []
    r_peaks = []
    last_env_peak = -refractory * 2
    last_r_peak = -refractory * 2

    for i in range(len(env)):
        thr = max(thresholds[i], 3.0)
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
        # 不应期检查用 lastPeakIdx（Kotlin 原版用精修后的 lastPeakIdx）
        if (i - last_r_peak) < refractory:
            continue

        # 精修 ±50ms
        r_lo, r_hi = max(0, i - sr // 20), min(len(hp), i + sr // 20)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j

        env_peaks.append(i)
        r_peaks.append(best_idx)
        last_env_peak = i
        last_r_peak = best_idx  # Kotlin 用精修后的 bestIdx 更新

    return env_peaks, r_peaks


def analyze(path, label):
    print(f"\n{'=' * 90}")
    print(f"样本: {label}")
    print(f"{'=' * 90}")
    if not os.path.exists(path):
        return
    ecg = load_ecg(path)
    sr = 500
    env, hp = compute_envelope(ecg, sr)
    env_peaks, r_peaks = detect_with_env_peaks(ecg, env, hp, sr)

    print(f"envelope 峰数: {len(env_peaks)}, 精修后 R 峰数: {len(r_peaks)}")

    # 对比精修前后 RR
    violations = []
    for i in range(1, len(r_peaks)):
        rr_env = (env_peaks[i] - env_peaks[i - 1]) * 1000 // sr
        rr_refined = (r_peaks[i] - r_peaks[i - 1]) * 1000 // sr
        # 精修后 RR < 200ms 但精修前 >= 200ms = 精修突破不应期
        if rr_refined < 200 and rr_env >= 200:
            violations.append((i, rr_env, rr_refined,
                               env_peaks[i - 1], env_peaks[i],
                               r_peaks[i - 1], r_peaks[i]))
        # 精修后 RR < 300ms（极端）不管精修前
        elif rr_refined < 300:
            violations.append((i, rr_env, rr_refined,
                               env_peaks[i - 1], env_peaks[i],
                               r_peaks[i - 1], r_peaks[i]))

    print(f"\n精修导致 RR < 300ms 的违规对: {len(violations)}")
    if violations:
        print(f"  {'序号':>4} {'envRRms':>8} {'精修RRms':>8} {'env峰1':>7} {'env峰2':>7} {'精修1':>7} {'精修2':>7} {'位移1':>6} {'位移2':>6}")
        for i, rr_env, rr_ref, e1, e2, r1, r2 in violations[:15]:
            shift1 = (r1 - e1) * 1000 // sr  # 精修位移（负=前移）
            shift2 = (r2 - e2) * 1000 // sr
            print(f"  {i:>4} {rr_env:>8} {rr_ref:>8} {e1/sr:>7.2f} {e2/sr:>7.2f} {r1/sr:>7.2f} {r2/sr:>7.2f} {shift1:>6} {shift2:>6}")

    # 统计：精修后 RR < 200ms 的数量
    sub_200 = sum(1 for i in range(1, len(r_peaks))
                  if (r_peaks[i] - r_peaks[i - 1]) * 1000 / sr < 200)
    sub_300 = sum(1 for i in range(1, len(r_peaks))
                  if (r_peaks[i] - r_peaks[i - 1]) * 1000 / sr < 300)
    print(f"\n精修后 RR < 200ms: {sub_200} 个 (生理不可能，纯算法伪迹)")
    print(f"精修后 RR < 300ms: {sub_300} 个 (极端 RR)")

    # 对比：如果不精修（用 envelope 峰位置），RR < 200ms 的数量
    env_sub_200 = sum(1 for i in range(1, len(env_peaks))
                      if (env_peaks[i] - env_peaks[i - 1]) * 1000 / sr < 200)
    env_sub_300 = sum(1 for i in range(1, len(env_peaks))
                      if (env_peaks[i] - env_peaks[i - 1]) * 1000 / sr < 300)
    print(f"不精修（env峰）RR < 200ms: {env_sub_200} 个")
    print(f"不精修（env峰）RR < 300ms: {env_sub_300} 个")


def main():
    samples = [
        ("静息(边缘)", "samples/静息.txt"),
        ("223211(不可信)", "samples/ECG_diagnostic_20260716_223211.txt"),
        ("运动后(可信)", "samples/运动后.txt"),
        ("心电api活动后(边缘)", "samples/心电api活动后.txt"),
    ]
    print("验证：精修 ±50ms 是否突破 200ms 不应期，产生 < 200ms 的超短 RR")
    for label, path in samples:
        analyze(path, label)


if __name__ == "__main__":
    main()
