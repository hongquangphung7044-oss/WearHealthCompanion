#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""详细诊断信号平均法在每个样本走哪个分支返回"""
import os
import glob
from statistics import median
from verify_fix_v4 import detect_r_peaks_fixed, parse_ecg, SR

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def diagnose_qt(ecg, r_peaks, avg_hr, sr=SR):
    n = len(ecg)
    if len(r_peaks) < 5:
        return "len(r_peaks)<5"
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
        return f"len(cycles)={len(cycles)}<5"
    min_len = min(e - s for s, e in cycles)
    if min_len < sr * 350 // 1000:
        return f"min_len={min_len}<{sr*350//1000}"
    avg = [0.0] * min_len
    for s, e in cycles:
        for i in range(min_len):
            avg[i] += ecg[s + i]
    avg = [x / len(cycles) for x in avg]
    bl_a_end = min(sr * 30 // 1000, min_len // 5)
    bl_b_start = max(min_len - sr * 30 // 1000, min_len * 4 // 5)
    bl_a = sum(avg[:bl_a_end]) / bl_a_end if bl_a_end > 0 else 0.0
    bl_b = sum(avg[bl_b_start:]) / (min_len - bl_b_start) if bl_b_start < min_len else 0.0
    baseline = (bl_a + bl_b) / 2.0
    avg_hp = [x - baseline for x in avg]
    r_pos_in_win = win_before
    r_val = avg_hp[r_pos_in_win]
    for j in range(max(0, r_pos_in_win - sr // 50), min(min_len, r_pos_in_win + sr // 50 + 1)):
        if abs(avg_hp[j]) > abs(r_val):
            r_val = avg_hp[j]
    r_amp = abs(r_val)
    if r_amp < 30:
        return f"r_amp={r_amp:.1f}<30"
    q_threshold = r_amp * 0.05
    q_pos_in_win = r_pos_in_win
    for i in range(r_pos_in_win, max(0, r_pos_in_win - sr * 80 // 1000) - 1, -1):
        if abs(avg_hp[i]) < q_threshold:
            q_pos_in_win = i
            break
    t_start_in_win = r_pos_in_win + sr * 100 // 1000
    t_end_in_win = min(min_len, r_pos_in_win + sr * 450 // 1000)
    if t_end_in_win <= t_start_in_win + 10:
        return f"t_window_too_narrow: t_end={t_end_in_win}, t_start={t_start_in_win}"
    sw = 7
    smoothed = []
    for i in range(t_end_in_win - t_start_in_win):
        s, e = max(0, i - sw // 2), min(t_end_in_win - t_start_in_win, i + sw // 2 + 1)
        smoothed.append(sum(avg_hp[t_start_in_win + j] for j in range(s, e)) / (e - s))
    t_idx = -1
    max_dev = 0.0
    for i in range(1, len(smoothed) - 1):
        is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                  (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
        if is_peak and abs(smoothed[i]) > max_dev:
            max_dev = abs(smoothed[i])
            t_idx = i
    if t_idx < 0:
        return f"t_idx<0 (smoothed range: min={min(smoothed):.1f}, max={max(smoothed):.1f})"
    noise_seg = [smoothed[i] for i in range(len(smoothed)) if abs(i - t_idx) > sr * 30 // 1000]
    if not noise_seg:
        noise_seg = smoothed[:]
    noise_rms = (sum(x * x for x in noise_seg) / len(noise_seg)) ** 0.5
    t_snr = max_dev / noise_rms if noise_rms > 0 else 0
    if t_snr < 2.5:
        return f"t_snr={t_snr:.2f}<2.5 (max_dev={max_dev:.1f}, noise_rms={noise_rms:.1f}, t_idx={t_idx})"
    # 切线法
    is_positive_t = smoothed[t_idx] > 0
    t_end_idx = t_idx
    if is_positive_t:
        max_down_slope, max_down_idx = 0.0, t_idx
        for i in range(t_idx, len(smoothed) - 1):
            slope = smoothed[i + 1] - smoothed[i]
            if slope < max_down_slope:
                max_down_slope, max_down_idx = slope, i
        if max_down_slope < 0:
            t_end_idx = int(max_down_idx - smoothed[max_down_idx] / max_down_slope)
            t_end_idx = max(t_idx, min(len(smoothed) - 1, t_end_idx))
    else:
        max_up_slope, max_up_idx = 0.0, t_idx
        for i in range(t_idx, len(smoothed) - 1):
            slope = smoothed[i + 1] - smoothed[i]
            if slope > max_up_slope:
                max_up_slope, max_up_idx = slope, i
        if max_up_slope > 0:
            t_end_idx = int(max_up_idx - smoothed[max_up_idx] / max_up_slope)
            t_end_idx = max(t_idx, min(len(smoothed) - 1, t_end_idx))
    t_end_in_win_global = t_start_in_win + t_end_idx
    qt_ms = (t_end_in_win_global - q_pos_in_win) * 1000 // sr
    if qt_ms < 250 or qt_ms > 500:
        return f"qt_ms={qt_ms} out of range, t_idx={t_idx}, t_end_idx={t_end_idx}, q_pos={q_pos_in_win}, t_snr={t_snr:.2f}"
    return f"OK: qt={qt_ms}ms, t_snr={t_snr:.2f}, t_idx={t_idx}, t_end_idx={t_end_idx}, q_pos={q_pos_in_win}, polarity={'pos' if is_positive_t else 'neg'}"


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
        if len(peaks) >= 3:
            rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
            valid_rr = [r for r in rr_list if 300 <= r <= 2500]
            local_hr = 60000 // (sum(valid_rr) // len(valid_rr)) if valid_rr else hv_hr
        else:
            local_hr = hv_hr
        result = diagnose_qt(ecg, peaks, local_hr)
        print(f"{os.path.basename(f)}: HV_HR={hv_hr}, local_HR={local_hr}, R波={len(peaks)} → {result}")


if __name__ == "__main__":
    main()
