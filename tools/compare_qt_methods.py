#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比所有 QT 估测方法"""
import os
import glob
from verify_fix_v7 import detect_r_peaks_fixed, estimate_qrs_snr_adaptive, _qt_prior_bazett, parse_ecg, SR

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    print("=" * 120)
    print("QT 估测方法对比")
    print("=" * 120)
    print(f"{'文件':<32} {'HV_HR':<6} {'HV_QT':<6} {'HV_QTc':<7} {'local_HR':<9} {'Bazett400':<10} {'Bazett370':<10} {'Bazett_HVQTc':<12}")
    print("-" * 120)
    bazett400_diffs = []
    bazett370_diffs = []
    bazett_hvqtc_diffs = []
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_hr = int(hv.get("平均心率", "0") or "0")
        hv_qt = int(hv.get("QT间期", "0") or "0")
        hv_qtc = int(hv.get("QTc", "0") or "0")
        if hv_hr == 0 or hv_qt == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        if len(peaks) >= 3:
            rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
            valid_rr = [r for r in rr_list if 300 <= r <= 2500]
            if valid_rr:
                valid_rr_sorted = sorted(valid_rr)
                rr_median = valid_rr_sorted[len(valid_rr_sorted) // 2]
                local_hr = 60000 // rr_median if rr_median > 0 else hv_hr
            else:
                local_hr = hv_hr
        else:
            local_hr = hv_hr
        # Bazett QTc=400
        b400 = int(400 * (60.0 / local_hr) ** 0.5) if local_hr > 0 else 0
        # Bazett QTc=370
        b370 = int(370 * (60.0 / local_hr) ** 0.5) if local_hr > 0 else 0
        # Bazett 用 HV_QTc（理论上不可能，但作为"理想先验"对比）
        b_hvqtc = int(hv_qtc * (60.0 / local_hr) ** 0.5) if local_hr > 0 else 0
        bazett400_diffs.append(abs(b400 - hv_qt))
        bazett370_diffs.append(abs(b370 - hv_qt))
        bazett_hvqtc_diffs.append(abs(b_hvqtc - hv_qt))
        print(f"{os.path.basename(f):<32} {hv_hr:<6} {hv_qt:<6} {hv_qtc:<7} {local_hr:<9} {b400:<4}({b400-hv_qt:+d}){'✓' if abs(b400-hv_qt)<=15 else '✗':<3} {b370:<4}({b370-hv_qt:+d}){'✓' if abs(b370-hv_qt)<=15 else '✗':<3} {b_hvqtc:<4}({b_hvqtc-hv_qt:+d}){'✓' if abs(b_hvqtc-hv_qt)<=15 else '✗':<3}")
    print()
    n = len(bazett400_diffs)
    print(f"平均偏差：Bazett(QTc=400)={sum(bazett400_diffs)/n:.1f}ms, Bazett(QTc=370)={sum(bazett370_diffs)/n:.1f}ms, Bazett(用HV_QTc)={sum(bazett_hvqtc_diffs)/n:.1f}ms")
    print()
    print("说明：")
    print("  Bazett(QTc=400)：AHA/ESC 正常范围中位数（生理学先验，非硬编码）")
    print("  Bazett(QTc=370)：拟合 5 样本得来（硬编码补正，用户反对）")
    print("  Bazett(用HV_QTc)：用 HeartVoice 的 QTc 反算（理论上限，本地无法实现）")


if __name__ == "__main__":
    main()
