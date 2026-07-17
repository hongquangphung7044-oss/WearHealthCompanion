"""
验证"锁 T 波"假设：在静息样本（T/R=1.80）上，分析极端 RR 对的来源。

假设：当 T 波振幅 ≥ R 波时，回溯补检或精修会把 T 波当 R 波，
产生"R-T 短 RR（约 300ms）+ T-下个R 长 RR（约 1200ms）"的伪配对。

验证方法：
1. 用 detect_v5 检测静息样本的 R 峰
2. 找出所有极端 RR 对（<600ms 短 + >1200ms 长成对出现）
3. 对每对，看短 RR 是否 ≈ tOffset（200-400ms，R-T 间距）
4. 看长 RR 是否 ≈ meanRR - tOffset（T-下个R 间距）
5. 如果匹配，确认是锁 T 波

同时对比：精修前的 envelope 峰位置 vs 精修后的 R 峰位置，
看精修是否把 envelope 峰（在 R 波附近）移到了 T 波上。
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope, detect_v5, filter_ectopic


def detect_v5_no_backfill(ecg, env, hp, sr=500):
    """detect_v5 但不做回溯补检，只返回主检测 + 精修的 R 峰"""
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

    refractory = sr // 5
    check_range = sr // 50
    r_peaks = []
    last_peak = -refractory * 2

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
        if (i - last_peak) < refractory:
            continue
        # 精修 ±50ms
        r_lo, r_hi = max(0, i - sr // 20), min(len(hp), i + sr // 20)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx
    return r_peaks, thresholds


def analyze_sample(path, label):
    print(f"\n{'=' * 80}")
    print(f"样本: {label} ({os.path.basename(path)})")
    print(f"{'=' * 80}")
    if not os.path.exists(path):
        print(f"  [文件不存在]")
        return
    ecg = load_ecg(path)
    sr = 500
    duration = len(ecg) / sr
    env, hp = compute_envelope(ecg, sr)

    # 主检测（无回溯补检）
    r_main, thresholds = detect_v5_no_backfill(ecg, env, hp, sr)
    # 完整检测（含回溯补检）
    r_full = detect_v5(ecg, env, hp, sr=sr)

    print(f"  时长: {duration:.1f}s, 主检测R峰: {len(r_main)}, 完整检测R峰: {len(r_full)}")
    print(f"  回溯补检新增: {len(r_full) - len(r_main)} 个")

    # 分析极端 RR 对（用完整检测）
    def rr_ms(a, b):
        return int((b - a) * 1000.0 / sr)

    short_long_pairs = []
    for i in range(1, len(r_full) - 1):
        rr_prev = rr_ms(r_full[i - 1], r_full[i])
        rr_next = rr_ms(r_full[i], r_full[i + 1])
        # 短-长配对：前 RR < 600ms，后 RR > 1200ms
        if rr_prev < 600 and rr_next > 1200:
            short_long_pairs.append((i, r_full[i], rr_prev, rr_next, r_full[i-1], r_full[i+1]))

    print(f"  极端短-长配对数: {len(short_long_pairs)}")
    if not short_long_pairs:
        print(f"  无短-长配对，跳过锁T波分析")
        return

    # 对每个短-长配对，分析中间的 R 峰是真 R 还是 T 波
    # 方法：看中间峰 r_full[i] 的 |hp| 值，与前后真 R 峰比较
    # 如果中间峰是 T 波，它的位置应该在真 R 峰后 200-400ms
    print(f"\n  短-长配对分析（前10个）:")
    print(f"  {'序号':>4} {'中间峰ms':>8} {'前RRms':>7} {'后RRms':>7} {'中间|hp|':>9} {'前R|hp|':>9} {'后R|hp|':>9} {'判定'}")
    t_wave_count = 0
    for idx, (i, mid_peak, rr_prev, rr_next, prev_peak, next_peak) in enumerate(short_long_pairs[:10]):
        mid_hp = abs(hp[mid_peak])
        prev_hp = abs(hp[prev_peak])
        next_hp = abs(hp[next_peak])
        # 中间峰位置（相对前 R 峰的 ms）
        mid_offset_ms = rr_ms(prev_peak, mid_peak)
        # 判定：如果中间峰 |hp| 明显小于前后 R 峰，且位置在 200-400ms（T 波位置），判为 T 波
        is_t_wave = (mid_hp < prev_hp * 0.7 and mid_hp < next_hp * 0.7
                     and 200 <= mid_offset_ms <= 450)
        if is_t_wave:
            t_wave_count += 1
        judge = "T波" if is_t_wave else "R波/其他"
        print(f"  {idx:>4} {mid_offset_ms:>8} {rr_prev:>7} {rr_next:>7} "
              f"{mid_hp:>9.1f} {prev_hp:>9.1f} {next_hp:>9.1f} {judge}")

    print(f"\n  锁T波比例: {t_wave_count}/{min(len(short_long_pairs), 10)} "
          f"({t_wave_count * 100 / min(len(short_long_pairs), 10):.0f}%)")

    # 进一步：看回溯补检的 R 峰是否落在 T 波位置
    r_main_set = set(r_main)
    backfill_peaks = [p for p in r_full if p not in r_main_set]
    if backfill_peaks:
        print(f"\n  回溯补检峰分析（共 {len(backfill_peaks)} 个，前10个）:")
        print(f"  {'序号':>4} {'位置s':>7} {'|hp|':>7} {'附近最大env':>10} {'判定'}")
        for idx, bp in enumerate(backfill_peaks[:10]):
            bp_hp = abs(hp[bp])
            # 找最近的主检测 R 峰
            nearest_main = min(r_main, key=lambda r: abs(r - bp))
            offset_ms = abs(bp - nearest_main) * 1000 // sr
            # 如果补检峰在主检测 R 峰后 200-400ms，可能是 T 波
            is_t = 200 <= (bp - nearest_main) * 1000 / sr <= 450 if bp > nearest_main else False
            judge = "T波?" if is_t else "其他"
            print(f"  {idx:>4} {bp/sr:>7.2f} {bp_hp:>7.1f} {'':>10} {judge} (距最近主R峰 {offset_ms}ms)")


def main():
    samples = [
        ("静息(T/R=1.80,SNB)", "samples/静息.txt"),
        ("运动后(T/R=0.97,SNT)", "samples/运动后.txt"),
        ("223211(T/R=1.09,SN)", "samples/ECG_diagnostic_20260716_223211.txt"),
        ("心电api活动后(SN)", "samples/心电api活动后.txt"),
        ("心电api静息(SNB)", "samples/心电api静息.txt"),
    ]
    print("验证假设：极端 RR 对来自精修/回溯补检锁 T 波")
    print("判定标准：短 RR ≈ 200-450ms（R-T 间距）+ 中间峰 |hp| < 前后 R 峰 70%")
    for label, path in samples:
        analyze_sample(path, label)


if __name__ == "__main__":
    main()
