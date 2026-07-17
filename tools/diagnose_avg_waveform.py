#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断信号平均法在样本4上的失败原因，并打印平均波形关键点"""
import os
import glob
from statistics import median
from verify_fix_v3 import parse_ecg, detect_r_peaks_fixed

SR = 500
SAMPLES_DIR = os.path.join(os.path.join(os.path.dirname(__file__), ".."), "samples")


def build_avg_waveform(ecg, r_peaks, sr=SR):
    n = len(ecg)
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
        return None
    min_len = min(e - s for s, e in cycles)
    avg = [0.0] * min_len
    for s, e in cycles:
        for i in range(min_len):
            avg[i] += ecg[s + i]
    avg = [x / len(cycles) for x in avg]
    bl_start, bl_end = 0, sr * 30 // 1000
    if bl_end > min_len:
        bl_end = min_len // 4
    baseline = sum(avg[bl_start:bl_end]) / (bl_end - bl_start) if bl_end > bl_start else 0.0
    avg_hp = [x - baseline for x in avg]
    return avg_hp, win_before, len(cycles)


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_qt = int(hv.get("QT间期", "0") or "0")
        hv_hr = int(hv.get("平均心率", "0") or "0")
        if hv_qt == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        res = build_avg_waveform(ecg, peaks)
        if not res:
            continue
        avg_hp, r_pos, n_cycles = res
        # 打印 R 峰前 30ms 到 R 峰后 450ms 的平均波形（每 10ms 采样）
        print("=" * 100)
        print(f"{os.path.basename(f)}  HV_HR={hv_hr}  HV_QT={hv_qt}ms  N周期={n_cycles}  期望T终偏移=R+{hv_qt-30}ms（Q在R-30ms近似）")
        print("=" * 100)
        # R 峰位置 = r_pos
        r_amp = abs(avg_hp[r_pos])
        # 找 T 峰和切线 T 终
        t_start = r_pos + SR * 100 // 1000
        t_end = min(len(avg_hp), r_pos + SR * 450 // 1000)
        # 平滑
        sw = 7
        smoothed = []
        for i in range(t_end - t_start):
            s, e = max(0, i - sw // 2), min(t_end - t_start, i + sw // 2 + 1)
            smoothed.append(sum(avg_hp[t_start + j] for j in range(s, e)) / (e - s))
        # 找 T 峰
        t_idx = -1
        max_dev = 0.0
        for i in range(1, len(smoothed) - 1):
            is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                      (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
            if is_peak and abs(smoothed[i]) > max_dev:
                max_dev = abs(smoothed[i])
                t_idx = i
        # 期望 T 终（用 HV_QT 估算）：Q≈R-30ms，T 终 = R + (HV_QT - 30) ms
        hv_t_end_offset = hv_qt - 30
        # 打印每 20ms 的波形
        print(f"  偏移ms   原始值    平滑值    备注")
        for ms in range(-30, 460, 20):
            idx = r_pos + ms * SR // 1000
            if idx < 0 or idx >= len(avg_hp):
                continue
            t_idx_in_smoothed = idx - t_start
            smoothed_val = smoothed[t_idx_in_smoothed] if 0 <= t_idx_in_smoothed < len(smoothed) else 0.0
            note = ""
            if ms == 0:
                note = "← R 峰"
            elif t_idx >= 0 and ms == (t_start + t_idx - r_pos) * 1000 // SR:
                note = f"← T 峰(振幅={max_dev:.0f})"
            elif ms == hv_t_end_offset:
                note = f"← 期望T终(HV)"
            print(f"  {ms:+4d}    {avg_hp[idx]:+7.1f}  {smoothed_val:+7.1f}  {note}")
        # 切线法找 T 终
        if t_idx >= 0:
            is_pos = smoothed[t_idx] > 0
            t_end_idx = t_idx
            if is_pos:
                max_down, max_down_i = 0.0, t_idx
                for i in range(t_idx, len(smoothed) - 1):
                    slope = smoothed[i + 1] - smoothed[i]
                    if slope < max_down:
                        max_down, max_down_i = slope, i
                if max_down < 0:
                    t_end_idx = int(max_down_i - smoothed[max_down_i] / max_down)
                    t_end_idx = max(t_idx, min(len(smoothed) - 1, t_end_idx))
            else:
                max_up, max_up_i = 0.0, t_idx
                for i in range(t_idx, len(smoothed) - 1):
                    slope = smoothed[i + 1] - smoothed[i]
                    if slope > max_up:
                        max_up, max_up_i = slope, i
                if max_up > 0:
                    t_end_idx = int(max_up_i - smoothed[max_up_i] / max_up)
                    t_end_idx = max(t_idx, min(len(smoothed) - 1, t_end_idx))
            t_end_offset_ms = (t_start + t_end_idx - r_pos) * 1000 // SR
            print(f"  → 切线法 T 终偏移: R+{t_end_offset_ms}ms  (期望 R+{hv_t_end_offset}ms)")
        print()


if __name__ == "__main__":
    main()
