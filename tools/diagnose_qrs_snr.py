#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 QRS SNR 分布"""
import os
import glob
from verify_fix_v8 import detect_r_peaks_fixed, estimate_qrs_snr_adaptive, parse_ecg

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_qrs = int(hv.get("QRS宽度", "0") or "0")
        if hv_qrs == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        snr_list = []
        qrs_list = []
        for r in peaks:
            res = estimate_qrs_snr_adaptive(ecg, r)
            if res:
                snr_list.append(res[4])
                if 40 <= res[0] <= 200:
                    qrs_list.append(res[0])
        if snr_list:
            snr_sorted = sorted(snr_list)
            print(f"{os.path.basename(f)}: R波={len(peaks)}, SNR min={snr_sorted[0]:.2f}, median={snr_sorted[len(snr_sorted)//2]:.2f}, max={snr_sorted[-1]:.2f}, 高SNR(>=3)={sum(1 for s in snr_list if s>=3)}/{len(snr_list)}")


if __name__ == "__main__":
    main()
