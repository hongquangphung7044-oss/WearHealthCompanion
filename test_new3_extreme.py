"""
深入分析三个新样本的极端 RR 来源（v6 修复后残余）。

v6 已修复：精修二次不应期 + 回溯补检T波排除
但仍判边缘（极端 RR 12-15%），需搞清楚残余极端 RR 的模式。
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope, filter_ectopic
from test_reliability import compute_rr, extract_segments, filter_noise_r_peaks
from test_refine_fix import detect_v5_fixed as detect_v6
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
    r_full = detect_v6(ecg, env, hp, sr)
    r_main, _ = detect_v5_no_backfill(ecg, env, hp, sr)
    r_main_set = set(r_main)
    backfill = [p for p in r_full if p not in r_main_set]

    print(f"主检测: {len(r_main)}, 回溯补检: {len(backfill)}, 完整: {len(r_full)}")

    # 极端 RR 分析（用完整检测）
    extreme_rrs = []
    for i in range(1, len(r_full)):
        rr = int((r_full[i] - r_full[i - 1]) * 1000 / sr)
        if not (300 <= rr <= 2500):
            extreme_rrs.append((i, rr, r_full[i - 1], r_full[i]))

    print(f"极端 RR 数: {len(extreme_rrs)} (总 RR {len(r_full)-1}个, 占比 {len(extreme_rrs)*100/max(1,len(r_full)-1):.0f}%)")
    if extreme_rrs:
        print(f"  {'序号':>4} {'RRms':>6} {'前峰s':>7} {'后峰s':>7} {'类型'}")
        short = 0
        long_ = 0
        for i, rr, p1, p2 in extreme_rrs:
            t = "超短(<300)" if rr < 300 else "超长(>2500)"
            if rr < 300:
                short += 1
            else:
                long_ += 1
            # 判断后峰是否是回溯补检
            is_backfill = p2 in set(backfill)
            bf_mark = "[补检]" if is_backfill else ""
            print(f"  {i:>4} {rr:>6} {p1/sr:>7.2f} {p2/sr:>7.2f} {t} {bf_mark}")
        print(f"  超短: {short}, 超长: {long_}")

    # 回溯补检峰位置分析
    if backfill:
        print(f"\n回溯补检峰 ({len(backfill)} 个):")
        t_wave = 0
        noise = 0
        refine_near = 0
        for bp in backfill:
            nearest = min(r_main, key=lambda r: abs(r - bp))
            offset = abs(bp - nearest) * 1000 / sr
            bp_hp = abs(hp[bp])
            if 200 <= offset <= 450:
                t_wave += 1
                judge = "T波位置(应被v6排除但仍补检?)"
            elif offset < 50:
                refine_near += 1
                judge = "精修近邻"
            else:
                noise += 1
                judge = "漏检补检"
            print(f"  位置 {bp/sr:.2f}s, |hp|={bp_hp:.1f}, 距主R峰 {offset:.0f}ms, {judge}")
        print(f"  分类: T波位置={t_wave}, 精修近邻={refine_near}, 漏检={noise}")

    # 主检测 RR 序列看长 RR（漏检）
    print(f"\n主检测 RR 序列（ms），标 L 为长 RR(>meanRR*1.5)：")
    rrs = []
    for i in range(1, len(r_main)):
        rr = int((r_main[i] - r_main[i - 1]) * 1000 / sr)
        rrs.append(rr)
    if rrs:
        mean_rr = sum(rrs) / len(rrs)
        print(f"  meanRR={mean_rr:.0f}ms")
        line = "  "
        for i, rr in enumerate(rrs):
            mark = "*" if not (300 <= rr <= 2500) else ("L" if rr > mean_rr * 1.5 else "")
            line += f"[{rr}{mark}]"
            if (i + 1) % 8 == 0:
                line += "\n  "
        print(line)


def main():
    samples = [
        ("专业(HV,SNB)", "samples/专业.txt"),
        ("ds均衡", "samples/ds均衡.txt"),
        ("dsmax", "samples/dsmax.txt"),
    ]
    print("三个新样本极端 RR 残余分析（v6 修复后）")
    for label, path in samples:
        analyze(path, label)


if __name__ == "__main__":
    main()
