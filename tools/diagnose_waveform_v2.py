#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""输出信号平均波形（每 10ms 一个点），理解 QRS/T 波形态"""
import os
import glob
from verify_fix_v4 import detect_r_peaks_fixed, parse_ecg, SR

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def build_avg_waveform(ecg, r_peaks, sr=SR):
    n = len(ecg)
    if len(r_peaks) < 5:
        return None, 0, 0
    win_before = sr * 80 // 1000
    win_after = sr * 450 // 1000
    win_len = win_before + win_after
    cycles = []
    for r_idx in r_peaks:
        start = r_idx - win_before
        end = r_idx + win_after
        if start < 0 or end >= n:
            continue
        next_r = None
        for p in r_peaks:
            if p > r_idx:
                next_r = p
                break
        if next_r is not None and end > next_r - sr * 50 // 1000:
            end = next_r - sr * 50 // 1000
        if end - start < win_len * 0.6:
            continue
        cycles.append((start, end))
    if len(cycles) < 5:
        return None, 0, 0
    min_len = min(e - s for s, e in cycles)
    avg = [0.0] * min_len
    for s, e in cycles:
        for i in range(min_len):
            avg[i] += ecg[s + i]
    avg = [x / len(cycles) for x in avg]
    # 基线
    bl_a_end = min(sr * 30 // 1000, min_len // 5)
    bl_b_start = max(min_len - sr * 30 // 1000, min_len * 4 // 5)
    bl_a = sum(avg[:bl_a_end]) / bl_a_end if bl_a_end > 0 else 0.0
    bl_b = sum(avg[bl_b_start:]) / (min_len - bl_b_start) if bl_b_start < min_len else 0.0
    baseline = (bl_a + bl_b) / 2.0
    avg_hp = [x - baseline for x in avg]
    return avg_hp, win_before, len(cycles)


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_hr = int(hv.get("平均心率", "0") or "0")
        hv_qt = int(hv.get("QT间期", "0") or "0")
        if hv_hr == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        avg_hp, r_pos, n_cycles = build_avg_waveform(ecg, peaks)
        if avg_hp is None:
            print(f"\n=== {os.path.basename(f)}: 无法构建平均波形 ===")
            continue
        print(f"\n=== {os.path.basename(f)} (HV_HR={hv_hr}, HV_QT={hv_qt}, R波={len(peaks)}, 周期数={n_cycles}) ===")
        print(f"平均波形（R峰在位置{r_pos}={r_pos*2}ms，每 10ms 一点）：")
        # 每 10ms = 5 samples 一个点
        print(f"  {'位置ms':<8} {'振幅mV':<8} {'说明'}")
        for i in range(0, len(avg_hp), 5):
            ms = (i - r_pos) * 2  # 相对 R 峰的 ms
            mv = avg_hp[i] / 1000.0
            mark = ""
            if i == r_pos:
                mark = "← R 峰"
            elif ms == -80:
                mark = "← 窗口起点"
            # 找局部极值
            if 0 < i < len(avg_hp) - 1:
                if avg_hp[i] > avg_hp[i-1] and avg_hp[i] > avg_hp[i+1] and abs(avg_hp[i]) > 30:
                    mark += " ↑峰"
                elif avg_hp[i] < avg_hp[i-1] and avg_hp[i] < avg_hp[i+1] and abs(avg_hp[i]) > 30:
                    mark += " ↓峰"
            print(f"  {ms:<+8} {mv:<+8.3f} {mark}")


if __name__ == "__main__":
    main()
