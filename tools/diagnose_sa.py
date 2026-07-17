#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断信号平均法周期被过滤的原因"""
import os
import glob
from verify_fix_v4 import detect_r_peaks_fixed, parse_ecg, SR

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_hr = int(hv.get("平均心率", "0") or "0")
        if hv_hr == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        n = len(ecg)
        print(f"\n=== {os.path.basename(f)} (HV_HR={hv_hr}, R波={len(peaks)}, 时长={n/SR:.1f}s) ===")
        # RR 分布
        rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
        if rr_list:
            print(f"  RR: min={min(rr_list)}ms, max={max(rr_list)}ms, median={sorted(rr_list)[len(rr_list)//2]}ms")
            short = [r for r in rr_list if r < 600]
            print(f"  短 RR(<600ms): {len(short)} 个, 值={short[:10]}")
        # 信号平均法周期过滤诊断
        win_before = SR * 80 // 1000  # 40
        win_after = SR * 450 // 1000   # 225
        win_len = win_before + win_after  # 265
        cycles = []
        rejected_reason = {"start<0": 0, "end>=n": 0, "end-start<0.6win": 0, "ok": 0}
        for r_idx in peaks:
            start = r_idx - win_before
            end = r_idx + win_after
            if start < 0:
                rejected_reason["start<0"] += 1
                continue
            if end >= n:
                rejected_reason["end>=n"] += 1
                continue
            next_r = None
            for p in peaks:
                if p > r_idx:
                    next_r = p
                    break
            if next_r is not None and end > next_r - SR * 50 // 1000:
                end = next_r - SR * 50 // 1000
            if end - start < win_len * 0.6:
                rejected_reason["end-start<0.6win"] += 1
                continue
            cycles.append((start, end))
            rejected_reason["ok"] += 1
        print(f"  信号平均周期过滤: {rejected_reason}")
        if cycles:
            min_len = min(e - s for s, e in cycles)
            print(f"  有效周期数={len(cycles)}, 最短周期长度={min_len} samples ({min_len*1000//SR}ms)")


if __name__ == "__main__":
    main()
