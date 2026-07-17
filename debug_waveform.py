"""查看误检簇附近的实际波形，确认误检的是什么波（S 波/T 波/基线漂移伪影）"""
import re


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def show_window(ecg, center, half_ms=300, sr=500, label=""):
    """显示 center 附近 ±half_ms 的波形（每 10ms 采样一个点，即每 5 个点）"""
    half = int(sr * half_ms / 1000)
    lo = max(0, center - half)
    hi = min(len(ecg), center + half)
    print(f"\n--- {label} idx={center} (t={center/sr:.3f}s) ±{half_ms}ms ---")
    print(f"{'idx':>6} {'t_ms':>6} {'raw':>8} {'局部min/max':>10}")
    # 找窗口内 min/max 用于参考
    seg = ecg[lo:hi]
    seg_min, seg_max = min(seg), max(seg)
    print(f"  窗口 raw 范围: {seg_min} ~ {seg_max} (峰峰值 {(seg_max-seg_min)/1000:.2f}mV)")
    # 每 10ms 打印一个点
    step = sr // 100  # 5 样本 = 10ms
    for i in range(lo, hi, step):
        t_ms = (i - center) * 1000 / sr
        # 标记是否是检测到的 R 峰
        mark = " <<R峰" if i == center else ""
        print(f"{i:>6} {t_ms:>+6.0f} {ecg[i]:>8}{mark}")


def show_qrs_shape(ecg, r_peaks, sr=500, window_ms=150):
    """对每个 R 峰，显示其前后 window_ms 的极值，判断 QRS 形态"""
    print(f"\n{'='*100}")
    print(f"QRS 形态分析（每个 R 峰 ±{window_ms}ms 内的极值，判断是 R 波/S 波/T 波/漂移伪影）")
    print(f"{'='*100}")
    print(f"{'idx':>6} {'RR_ms':>7} | {'前min':>20} {'R峰':>8} {'后max':>20} | {'形态判断'}")
    half = int(sr * window_ms / 1000)
    prev = None
    for idx in r_peaks:
        lo = max(0, idx - half)
        hi = min(len(ecg), idx + half)
        seg = ecg[lo:hi]
        # R 峰前后的极值
        pre_seg = ecg[max(0, idx-half):idx]
        post_seg = ecg[idx:min(len(ecg), idx+half)]
        pre_min = min(pre_seg) if pre_seg else 0
        pre_max = max(pre_seg) if pre_seg else 0
        post_min = min(post_seg) if post_seg else 0
        post_max = max(post_seg) if post_seg else 0
        rr_ms = int((idx - prev) * 1000 / sr) if prev is not None else 0
        r_val = ecg[idx]
        # 形态判断
        # 1秒局部基线（去DC）
        base_lo = max(0, idx - sr)
        base_hi = min(len(ecg), idx + sr)
        local_base = sum(ecg[base_lo:base_hi]) / (base_hi - base_lo)
        dev = r_val - local_base
        polarity = "正" if dev > 0 else "负"
        # 判断是否成对（前后 400ms 内有另一个 R 峰）
        paired = ""
        if prev is not None and rr_ms < 400:
            paired = " ← 成对(短RR)"
        print(f"{idx:>6} {rr_ms:>7} | {pre_min:>8}({(pre_min-local_base)/1000:+.2f}mV) ~ {pre_max:>8} "
              f"{r_val:>8}({dev/1000:+.2f}mV) "
              f"{post_min:>8} ~ {post_max:>8}({(post_max-local_base)/1000:+.2f}mV) | "
              f"{polarity}向 dev={dev/1000:+.2f}mV{paired}")
        prev = idx


def main():
    ecg = load_ecg("samples/ECG_diagnostic_20260716_223211.txt")
    r_peaks = [7,160,783,1217,1449,2459,2867,3323,3701,3818,4298,4562,4789,5179,5565,6061,
               6538,6648,7117,7217,8435,8635,9406,10204,10297,10517,11369,11693,12135,12581,
               12854,12998,13138,13283,13660,13933,14055,14425,14747,14912]

    # 整体基线漂移
    print(f"整体 raw 范围: {min(ecg)} ~ {max(ecg)} (DC 偏置约 {sum(ecg)//len(ecg)})")
    # 看 1 秒基线的变化（每秒一个点）
    print("\n1 秒窗口基线漂移（每秒采样）：")
    for s in range(0, 30, 2):
        seg = ecg[s*500:(s+1)*500]
        if seg:
            print(f"  t={s}s: raw 均值={sum(seg)//len(seg)}, 范围 {min(seg)}~{max(seg)}")

    # QRS 形态分析
    show_qrs_shape(ecg, r_peaks)

    # 看第一簇（idx 7, 160 双负峰，间隔 306ms）
    show_window(ecg, 7, half_ms=400, label="簇1: idx 7,160 双负峰")

    # 看 4 峰簇（idx 12854,12998,13138,13283）
    show_window(ecg, 13100, half_ms=600, label="簇6: idx 12854-13283 四峰簇")


if __name__ == "__main__":
    main()
