#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""详细诊断 QT 估测，找出 T 波定位偏长的根因。"""
import os
import glob
from statistics import median

SAMPLES_DIR = os.path.join(os.path.join(os.path.dirname(__file__), ".."), "samples")
SR = 500


def parse_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    ecg, hv = [], {}
    section = None
    for line in lines:
        s = line.strip()
        if s.startswith("[原始 ECG 数据]"):
            section = "raw"
        elif s.startswith("[HeartVoice API 返回]"):
            section = "hv"
        elif s.startswith("[算法提取后的结构化特征]"):
            section = None
        elif section == "raw":
            if s and not s.startswith("#") and s.lstrip("-").isdigit():
                ecg.append(int(s))
        elif section == "hv":
            if s and not s.startswith("#") and ":" in s:
                k, v = s.split(":", 1)
                hv[k.strip()] = v.strip()
    return ecg, hv


def detect_r_peaks(ecg, sr=SR):
    """复用 verify_fix 的 R 波检测"""
    n = len(ecg)
    if n < sr * 5:
        return []
    bw = sr
    baseline = [0.0] * n
    for i in range(n):
        s, e = max(0, i - bw // 2), min(n, i + bw // 2)
        baseline[i] = sum(ecg[s:e]) / (e - s)
    hp = [ecg[i] - baseline[i] for i in range(n)]
    grad = [0.0] * n
    for i in range(n):
        prev = hp[max(0, i - 1)]
        nxt = hp[min(n - 1, i + 1)]
        grad[i] = abs(nxt - prev) / 2.0
    sw = int(sr * 0.150)
    env = [0.0] * n
    for i in range(n):
        s, e = max(0, i - sw // 2), min(n, i + sw // 2 + 1)
        env[i] = sum(grad[s:e]) / (e - s)
    seg_len = sr * 4
    thr_arr = [0.0] * n
    for ss in range(0, n, seg_len):
        se = min(n, ss + seg_len)
        seg = env[ss:se]
        m = sum(seg) / len(seg)
        var = sum((x - m) ** 2 for x in seg) / len(seg)
        sd = var ** 0.5
        for i in range(ss, se):
            thr_arr[i] = m + 1.7 * sd
    floor = 3.0
    refractory = sr // 5
    refine_refractory = int(sr * 0.360)
    peaks = []
    last_peak = -refractory * 2
    check_range = sr // 50
    for i in range(n):
        thr = max(thr_arr[i], floor)
        if env[i] < thr:
            continue
        lo, hi = max(0, i - check_range), min(n, i + check_range + 1)
        is_max = True
        for j in range(lo, hi):
            if j != i and env[j] >= env[i]:
                if j < i:
                    is_max = False
                    break
        if not is_max:
            continue
        if (i - last_peak) < refractory:
            continue
        r_lo, r_hi = max(0, i - sr // 20), min(n, i + sr // 20)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        if peaks and (best_idx - peaks[-1]) < refine_refractory:
            continue
        if peaks:
            offset_ms = (best_idx - peaks[-1]) * 1000.0 / sr
            if 300.0 <= offset_ms <= 450.0:
                prev_r_amp = abs(hp[peaks[-1]])
                curr_amp = abs(hp[best_idx])
                if curr_amp < prev_r_amp * 0.6:
                    continue
        peaks.append(best_idx)
        last_peak = best_idx
    return peaks


def diagnose_qt_for_sample(ecg, r_peaks, sr=SR):
    """对前 5 个 R 波输出 T 波定位细节"""
    details = []
    for idx, r_idx in enumerate(r_peaks[:5]):
        q_start = max(0, r_idx - sr * 80 // 1000)
        s_end = min(len(ecg), r_idx + sr * 80 // 1000)
        if s_end - q_start < 10:
            continue
        seg = ecg[q_start:s_end]
        seg_med = median(seg)
        rr = sr // 50
        r_val = ecg[r_idx]
        for j in range(max(q_start, r_idx - rr), min(s_end, r_idx + rr + 1)):
            if abs(ecg[j] - seg_med) > abs(r_val - seg_med):
                r_val = ecg[j]
        r_amp = abs(r_val - seg_med)
        # Q point (5% 阈值)
        thr = r_amp * 0.05
        q_point = r_idx
        for i in range(r_idx, q_start - 1, -1):
            if abs(ecg[i] - seg_med) < thr:
                q_point = i
                break
        # T 波搜索
        t_start = r_idx + sr * 100 // 1000
        t_hard_end_500 = r_idx + sr * 500 // 1000  # 旧版
        t_hard_end_400 = r_idx + sr * 400 // 1000  # 新版
        next_lim = (r_peaks[idx + 1] - sr * 50 // 1000) if (idx + 1 < len(r_peaks)) else len(ecg)
        t_end_500 = min(len(ecg), t_hard_end_500, next_lim)
        t_end_400 = min(len(ecg), t_hard_end_400, next_lim)

        # 在 400ms 窗口找 T 峰
        def find_t_peak(t_end):
            if t_end <= t_start + 10:
                return None, None, None
            t_seg = ecg[t_start:t_end]
            t_med = median(t_seg)
            sw = 5
            smoothed = []
            for i in range(len(t_seg)):
                s, e = max(0, i - sw // 2), min(len(t_seg), i + sw // 2 + 1)
                smoothed.append(sum(t_seg[s:e]) / (e - s) - t_med)
            t_idx = -1
            max_dev = 0.0
            for i in range(1, len(smoothed) - 1):
                is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                          (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
                if is_peak and abs(smoothed[i]) > max_dev:
                    max_dev = abs(smoothed[i])
                    t_idx = i
            if t_idx < 0:
                return None, None, None
            t_peak_global = t_start + t_idx
            # 切线法找 T 波终点
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
            t_end_global = t_start + t_end_idx
            return t_peak_global, t_end_global, is_pos

        t_peak_400, t_end_400_g, is_pos_400 = find_t_peak(t_end_400)
        t_peak_500, t_end_500_g, is_pos_500 = find_t_peak(t_end_500)

        # 时间偏移
        def ms(x):
            return (x - r_idx) * 1000 // sr if x else None
        def qt_ms(x):
            return (x - q_point) * 1000 // sr if x else None

        details.append({
            "r_idx": r_idx,
            "r_amp_mv": r_amp / 1000.0,
            "q_offset_ms": (q_point - r_idx) * 1000 // sr,
            "t_peak_400_ms": ms(t_peak_400),
            "t_end_400_ms": ms(t_end_400_g),
            "qt_400_peak": qt_ms(t_peak_400),
            "qt_400_end": qt_ms(t_end_400_g),
            "t_peak_500_ms": ms(t_peak_500),
            "t_end_500_ms": ms(t_end_500_g),
            "qt_500_peak": qt_ms(t_peak_500),
            "qt_500_end": qt_ms(t_end_500_g),
            "is_pos": is_pos_400,
        })
    return details


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_qt = int(hv.get("QT间期", "0") or "0")
        if hv_qt == 0:
            continue
        peaks = detect_r_peaks(ecg)
        details = diagnose_qt_for_sample(ecg, peaks)
        print("=" * 110)
        print(f"{os.path.basename(f)}  HV_QT={hv_qt}ms  R波数={len(peaks)}")
        print("=" * 110)
        print(f"  {'R峰':<6} {'R振幅':<7} {'Q偏移':<7} {'T峰_400':<9} {'T终_400':<9} {'QT_400_峰':<10} {'QT_400_终':<10} {'T峰_500':<9} {'T终_500':<9} {'QT_500_终':<10} {'极性':<5}")
        for d in details:
            print(f"  {d['r_idx']:<6} {d['r_amp_mv']:<7.3f} {d['q_offset_ms']:<+7} "
                  f"{str(d['t_peak_400_ms']):<9} {str(d['t_end_400_ms']):<9} {str(d['qt_400_peak']):<10} {str(d['qt_400_end']):<10} "
                  f"{str(d['t_peak_500_ms']):<9} {str(d['t_end_500_ms']):<9} {str(d['qt_500_end']):<10} {'正' if d['is_pos'] else '负':<5}")
        print()


if __name__ == "__main__":
    main()
