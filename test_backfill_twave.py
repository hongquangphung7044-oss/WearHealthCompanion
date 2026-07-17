"""
深入分析静息样本的极端 RR 来源 + 回溯补检锁 T 波的量化。

静息样本主检测 32 个 R 峰，回溯补检 +8 = 40 个。
但 test_reliability 显示极端 RR 13%（边缘）。
需要搞清楚：这 13% 极端 RR 是回溯补检导致的，还是主检测本身的问题？
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope, detect_v5, filter_ectopic
from test_twave_lock import detect_v5_no_backfill


def analyze(path, label):
    print(f"\n{'=' * 90}")
    print(f"样本: {label}")
    print(f"{'=' * 90}")
    if not os.path.exists(path):
        return
    ecg = load_ecg(path)
    sr = 500
    env, hp = compute_envelope(ecg, sr)
    r_main, _ = detect_v5_no_backfill(ecg, env, hp, sr)
    r_full = detect_v5(ecg, env, hp, sr=sr)
    r_main_set = set(r_main)
    backfill = [p for p in r_full if p not in r_main_set]

    print(f"主检测: {len(r_main)}, 回溯补检: {len(backfill)}, 完整: {len(r_full)}")

    # 主检测 RR vs 完整 RR 的极端率对比
    def extreme_ratio(peaks):
        if len(peaks) < 2:
            return 0, 0, 0
        total = len(peaks) - 1
        extreme = 0
        valid = 0
        for i in range(1, len(peaks)):
            rr = int((peaks[i] - peaks[i - 1]) * 1000 / sr)
            if 300 <= rr <= 2500:
                valid += 1
            else:
                extreme += 1
        return extreme / total, valid / total, total

    main_ext, main_valid, main_total = extreme_ratio(r_main)
    full_ext, full_valid, full_total = extreme_ratio(r_full)
    print(f"主检测 RR: {main_total} 个, 极端率 {main_ext*100:.0f}%, 有效率 {main_valid*100:.0f}%")
    print(f"完整 RR:   {full_total} 个, 极端率 {full_ext*100:.0f}%, 有效率 {full_valid*100:.0f}%")
    print(f"回溯补检导致极端率上升: {main_ext*100:.0f}% → {full_ext*100:.0f}%")

    # 回溯补检峰的位置分析：距最近主检测 R 峰的偏移
    if backfill:
        print(f"\n回溯补检峰 ({len(backfill)} 个) 距最近主检测 R 峰的偏移:")
        t_wave = 0  # 200-450ms = T 波位置
        noise = 0   # >500ms 或不规则 = 噪声/漏检补检
        refine = 0  # <50ms = 精修重复
        print(f"  {'位置s':>7} {'|hp|':>7} {'偏移ms':>7} {'判定'}")
        for bp in backfill:
            nearest = min(r_main, key=lambda r: abs(r - bp))
            offset_ms = abs(bp - nearest) * 1000 // sr
            bp_hp = abs(hp[bp])
            if 200 <= offset_ms <= 450:
                judge = "T波位置"
                t_wave += 1
            elif offset_ms < 50:
                judge = "精修近邻(可能重复)"
                refine += 1
            else:
                judge = "漏检补检/噪声"
                noise += 1
            print(f"  {bp/sr:>7.2f} {bp_hp:>7.1f} {offset_ms:>7} {judge}")
        print(f"\n  回溯补检分类: T波位置={t_wave}, 精修近邻={refine}, 漏检/噪声={noise}")

    # 主检测的 RR 序列：看是否有长 RR（漏检）
    print(f"\n主检测 RR 序列（ms），标*为极端:")
    rrs = []
    for i in range(1, len(r_main)):
        rr = int((r_main[i] - r_main[i - 1]) * 1000 / sr)
        rrs.append(rr)
    if rrs:
        mean_rr = sum(rrs) / len(rrs)
        print(f"  主检测 meanRR={mean_rr:.0f}ms")
        line = "  "
        for i, rr in enumerate(rrs):
            mark = "*" if not (300 <= rr <= 2500) else ("L" if rr > mean_rr * 1.5 else "")
            line += f"[{rr}{mark}]"
            if (i + 1) % 8 == 0:
                line += "\n  "
        print(line)


def main():
    samples = [
        ("静息(SNB,边缘)", "samples/静息.txt"),
        ("223211(SN,不可信)", "samples/ECG_diagnostic_20260716_223211.txt"),
        ("运动后(SNT,可信)", "samples/运动后.txt"),
        ("心电api活动后(SN,边缘)", "samples/心电api活动后.txt"),
    ]
    print("回溯补检锁 T 波量化分析")
    for label, path in samples:
        analyze(path, label)


if __name__ == "__main__":
    main()
