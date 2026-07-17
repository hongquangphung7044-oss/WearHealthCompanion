#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 v6：local_hr、实测 QT、先验 QT、贝叶斯 QT"""
import os
import glob
from verify_fix_v6 import detect_r_peaks_fixed, estimate_qrs_snr_adaptive, _qt_prior_bazett, _cycle_quality_score, parse_ecg, SR

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")


def diagnose(ecg, r_peaks, avg_hr, qrs_width_ms, sr=SR):
    n = len(ecg)
    win_before = sr * 80 // 1000
    win_after = sr * 450 // 1000
    win_len = win_before + win_after
    cycles_with_q = []
    for idx, r_idx in enumerate(r_peaks):
        start = r_idx - win_before
        end = r_idx + win_after
        if start < 0 or end >= n:
            continue
        next_r = r_peaks[idx + 1] if idx + 1 < len(r_peaks) else None
        if next_r is not None and end > next_r - sr * 50 // 1000:
            end = next_r - sr * 50 // 1000
        if end - start < win_len * 0.6:
            continue
        q_score = _cycle_quality_score(ecg, r_idx, next_r, sr)
        if q_score > 0:
            cycles_with_q.append((start, end, q_score))
    if len(cycles_with_q) < 5:
        return "cycles<5"
    cycles_with_q.sort(key=lambda x: x[2], reverse=True)
    top_half = cycles_with_q[:max(5, len(cycles_with_q) // 2)]
    cycles = [(s, e) for s, e, _ in top_half]
    print(f"    质量分 top5: {[round(q,2) for _,_,q in top_half[:5]]}")
    print(f"    质量分 bot5: {[round(q,2) for _,_,q in cycles_with_q[-5:]]}")
    min_len = min(e - s for s, e in cycles)
    n_cycles = len(cycles)
    avg = [0.0] * min_len
    for pos in range(min_len):
        vals = sorted(ecg[s + pos] for s, e in cycles)
        avg[pos] = vals[n_cycles // 2]
    bl_start = 0
    bl_end = min(sr * 30 // 1000, win_before - sr * 10 // 1000)
    baseline = sum(avg[bl_start:bl_end]) / (bl_end - bl_start) if bl_end > bl_start else 0.0
    avg_hp = [x - baseline for x in avg]
    r_pos_in_win = win_before
    r_val = avg_hp[r_pos_in_win]
    for j in range(max(0, r_pos_in_win - sr * 20 // 1000), min(min_len, r_pos_in_win + sr * 20 // 1000 + 1)):
        if abs(avg_hp[j]) > abs(r_val):
            r_val = avg_hp[j]
    r_amp = abs(r_val)
    qrs_half_ms = max(20, min(80, qrs_width_ms // 2))
    q_pos_in_win = max(0, r_pos_in_win - qrs_half_ms * sr // 1000)
    t_start_in_win = r_pos_in_win + sr * 100 // 1000
    t_end_in_win = min(min_len, r_pos_in_win + sr * 450 // 1000)
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
    noise_seg = [smoothed[i] for i in range(len(smoothed)) if abs(i - t_idx) > sr * 30 // 1000]
    if not noise_seg:
        noise_seg = smoothed[:]
    noise_rms = (sum(x * x for x in noise_seg) / len(noise_seg)) ** 0.5
    t_snr = max_dev / noise_rms if noise_rms > 0 else 0
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
    t_end_global = t_start_in_win + t_end_idx
    qt_measured = (t_end_global - q_pos_in_win) * 1000 // sr
    qt_prior, _, _, _ = _qt_prior_bazett(avg_hr)
    t_peak_ms = (t_start_in_win + t_idx - r_pos_in_win) * 1000 // sr
    t_end_ms = (t_end_global - r_pos_in_win) * 1000 // sr
    q_ms = (q_pos_in_win - r_pos_in_win) * 1000 // sr
    print(f"    baseline={baseline:.1f}, r_amp={r_amp:.1f}, q_pos={q_ms}ms(相对R), T峰={t_peak_ms}ms(相对R), T终={t_end_ms}ms(相对R)")
    print(f"    实测QT={qt_measured}ms, 先验QT={qt_prior}ms(avg_hr={avg_hr}), T_SNR={t_snr:.2f}, T极性={'正' if is_positive_t else '负'}")


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
        if len(peaks) >= 3:
            rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
            valid_rr = [r for r in rr_list if 300 <= r <= 2500]
            local_hr_mean = 60000 // (sum(valid_rr) // len(valid_rr)) if valid_rr else hv_hr
            # 中位数心率
            valid_rr_sorted = sorted(valid_rr)
            rr_median = valid_rr_sorted[len(valid_rr_sorted) // 2] if valid_rr_sorted else 0
            local_hr_median = 60000 // rr_median if rr_median > 0 else hv_hr
        else:
            local_hr_mean = local_hr_median = hv_hr
        qrs_vals = []
        for r in peaks:
            res = estimate_qrs_snr_adaptive(ecg, r)
            if res and 40 <= res[0] <= 200:
                qrs_vals.append(res[0])
        new_qrs = sum(qrs_vals) // len(qrs_vals) if qrs_vals else 0
        print(f"\n=== {os.path.basename(f)} (HV_HR={hv_hr}, HV_QT={hv_qt}, R波={len(peaks)}) ===")
        print(f"  local_hr(均值)={local_hr_mean}, local_hr(中位数)={local_hr_median}, 差={local_hr_mean-local_hr_median}")
        diagnose(ecg, peaks, local_hr_mean, new_qrs)


if __name__ == "__main__":
    main()
