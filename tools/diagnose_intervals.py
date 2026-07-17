#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
精确诊断 QRS/QT 偏差根因
用 Kotlin 算法的真实参数，对每个 R 波输出 Q/S/T 定位细节
"""
import os
import glob
from statistics import median

SAMPLES_DIR = os.path.join(os.path.join(os.path.dirname(__file__), ".."), "samples")
SR = 500


def parse_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    ecg = []
    section = None
    hv = {}
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


def detect_r_peaks_kt(ecg, sr=SR):
    """精确复现 Kotlin detectRPeaks"""
    n = len(ecg)
    if n < sr * 5:
        return []
    # 1. 去基线（1s 窗口移动平均）
    bw = sr
    baseline = [0.0] * n
    for i in range(n):
        s = max(0, i - bw // 2)
        e = min(n, i + bw // 2)
        baseline[i] = sum(ecg[s:e]) / (e - s)
    hp = [ecg[i] - baseline[i] for i in range(n)]
    # 2. |梯度|
    grad = [0.0] * n
    for i in range(n):
        prev = hp[max(0, i - 1)]
        nxt = hp[min(n - 1, i + 1)]
        grad[i] = abs(nxt - prev) / 2.0
    # 3. 150ms boxcar 平滑
    sw = int(sr * 0.150)
    env = [0.0] * n
    for i in range(n):
        s = max(0, i - sw // 2)
        e = min(n, i + sw // 2 + 1)
        env[i] = sum(grad[s:e]) / (e - s)
    # 4. 分段阈值 4s mean+1.7std
    seg_len = sr * 4
    thr_arr = [0.0] * n
    for ss in range(0, n, seg_len):
        se = min(n, ss + seg_len)
        seg = env[ss:se]
        m = sum(seg) / len(seg)
        var = sum((x - m) ** 2 for x in seg) / len(seg)
        sd = var ** 0.5
        t = m + 1.7 * sd
        for i in range(ss, se):
            thr_arr[i] = t
    floor = 3.0
    # 5. R 峰检测
    refractory = sr // 5  # 200ms
    refine_refractory = int(sr * 0.300)  # 300ms
    peaks = []
    last_peak = -refractory * 2
    check_range = sr // 50  # ±10ms
    for i in range(n):
        thr = max(thr_arr[i], floor)
        if env[i] < thr:
            continue
        lo = max(0, i - check_range)
        hi = min(n, i + check_range + 1)
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
        # 精修 ±50ms
        r_lo = max(0, i - sr // 20)
        r_hi = min(n, i + sr // 20)
        best_idx = i
        best_val = 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        if peaks and (best_idx - peaks[-1]) < refine_refractory:
            continue
        peaks.append(best_idx)
        last_peak = best_idx
    # 7. 回溯补检
    if len(peaks) >= 5:
        extra = []
        for k in range(1, len(peaks)):
            rr = peaks[k] - peaks[k - 1]
            rs = max(0, k - 4)
            re = min(len(peaks), k + 4)
            recent = []
            for m in range(rs + 1, re):
                if m == k:
                    continue
                recent.append(peaks[m] - peaks[m - 1])
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (peaks[k - 1] + peaks[k]) // 2
                mid_thr = thr_arr[mid] if 0 <= mid < n else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss = peaks[k - 1] + refractory
                se = peaks[k] - refractory
                if se <= ss:
                    continue
                bb_idx = -1
                bb_val = back_thr
                for j in range(ss, se + 1):
                    if j < 0 or j >= n:
                        continue
                    if env[j] > bb_val:
                        blo = max(0, j - check_range)
                        bhi = min(n, j + check_range + 1)
                        ism = True
                        for m in range(blo, bhi):
                            if m != j and env[m] > env[j]:
                                ism = False
                                break
                        if ism:
                            bb_val = env[j]
                            bb_idx = j
                if bb_idx >= 0:
                    r_lo = max(0, bb_idx - sr // 20)
                    r_hi = min(n, bb_idx + sr // 20)
                    r_idx = bb_idx
                    r_val = 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    prev_r = max([p for p in peaks if p < r_idx] + [-refractory * 4])
                    offset_ms = (r_idx - prev_r) * 1000.0 / sr
                    if 300.0 <= offset_ms <= 450.0:
                        continue
                    if any(abs(r_idx - p) < refine_refractory for p in peaks):
                        continue
                    extra.append(r_idx)
        if extra:
            peaks.extend(extra)
            peaks.sort()
    return peaks


def diagnose_qrs_qt(ecg, peaks, sr=SR):
    """对每个 R 波诊断 QRS/QT 定位"""
    n = len(ecg)
    results = []
    for idx, r in enumerate(peaks):
        q_start = max(0, r - sr * 80 // 1000)
        s_end = min(n, r + sr * 80 // 1000)
        if s_end - q_start < 10:
            continue
        seg = ecg[q_start:s_end]
        seg_med = median(seg)
        # R 峰值 ±10ms
        rr = sr // 50
        r_val = ecg[r]
        for j in range(max(q_start, r - rr), min(s_end, r + rr + 1)):
            if abs(ecg[j] - seg_med) > abs(r_val - seg_med):
                r_val = ecg[j]
        r_amp = abs(r_val - seg_med)
        if r_amp < 30:
            continue
        # QRS 10% 阈值
        thr10 = r_amp * 0.10
        q10 = r
        for i in range(r, q_start - 1, -1):
            if abs(ecg[i] - seg_med) < thr10:
                q10 = i
                break
        s10 = r
        for i in range(r, s_end):
            if abs(ecg[i] - seg_med) < thr10:
                s10 = i
                break
        qrs10 = (s10 - q10) * 1000 // sr
        # QRS 5% 阈值（更低，量更宽）
        thr5 = r_amp * 0.05
        q5 = r
        for i in range(r, q_start - 1, -1):
            if abs(ecg[i] - seg_med) < thr5:
                q5 = i
                break
        s5 = r
        for i in range(r, s_end):
            if abs(ecg[i] - seg_med) < thr5:
                s5 = i
                break
        qrs5 = (s5 - q5) * 1000 // sr
        # QT：T 波峰
        t_start = r + sr * 100 // 1000
        t_hard_end = r + sr * 500 // 1000
        next_lim = (peaks[idx + 1] - sr * 50 // 1000) if (idx + 1 < len(peaks)) else n
        t_end = min(n, t_hard_end, next_lim)
        t_info = None
        if t_end > t_start + 10:
            t_seg = ecg[t_start:t_end]
            t_med = median(t_seg)
            sw = 5
            smoothed = []
            for i in range(len(t_seg)):
                s = max(0, i - sw // 2)
                e = min(len(t_seg), i + sw // 2 + 1)
                smoothed.append(sum(t_seg[s:e]) / (e - s) - t_med)
            t_idx = -1
            max_dev = 0.0
            for i in range(1, len(smoothed) - 1):
                is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                          (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
                if is_peak and abs(smoothed[i]) > max_dev:
                    max_dev = abs(smoothed[i])
                    t_idx = i
            if t_idx >= 0:
                t_global = t_start + t_idx
                qt_peak = (t_global - q10) * 1000 // sr
                t_offset_from_r = (t_global - r) * 1000 // sr  # T 波距 R 峰的偏移
                t_info = (qt_peak, t_global, t_offset_from_r, max_dev)
        results.append({
            "r_idx": r, "r_time_ms": r * 1000 // sr, "r_amp": r_amp,
            "q10": q10, "s10": s10, "qrs10": qrs10,
            "q5": q5, "s5": s5, "qrs5": qrs5,
            "t_info": t_info,
        })
    return results


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_qrs = int(hv.get("QRS宽度", "0") or "0")
        hv_qt = int(hv.get("QT间期", "0") or "0")
        if hv_qrs == 0:
            continue
        print("=" * 110)
        print(f"文件: {os.path.basename(f)}")
        print(f"HeartVoice: QRS={hv_qrs}ms  QT={hv_qt}ms  HR={hv.get('平均心率','?')}")
        print("=" * 110)
        peaks = detect_r_peaks_kt(ecg)
        print(f"Python 复现 R 波数: {len(peaks)}")
        diag = diagnose_qrs_qt(ecg, peaks)
        # 汇总
        qrs10_vals = [d["qrs10"] for d in diag if 40 <= d["qrs10"] <= 200]
        qrs5_vals = [d["qrs5"] for d in diag if 40 <= d["qrs5"] <= 200]
        qt_vals = [d["t_info"][0] for d in diag if d["t_info"] and 250 <= d["t_info"][0] <= 600]
        t_offsets = [d["t_info"][2] for d in diag if d["t_info"]]
        print(f"QRS(10%阈值): 均值={sum(qrs10_vals)//len(qrs10_vals) if qrs10_vals else 0}ms  样本数={len(qrs10_vals)}")
        print(f"QRS(5%阈值):  均值={sum(qrs5_vals)//len(qrs5_vals) if qrs5_vals else 0}ms  样本数={len(qrs5_vals)}")
        print(f"QT(T峰法):    均值={sum(qt_vals)//len(qt_vals) if qt_vals else 0}ms  样本数={len(qt_vals)}")
        print(f"T波距R峰偏移: 均值={sum(t_offsets)//len(t_offsets) if t_offsets else 0}ms  范围={min(t_offsets) if t_offsets else 0}-{max(t_offsets) if t_offsets else 0}ms")
        print()
        # 逐 R 波细节（前 8 个）
        print(f"{'R峰ms':<8} {'R振幅':<8} {'Q10':<6} {'S10':<6} {'QRS10':<7} {'Q5':<6} {'S5':<6} {'QRS5':<7} {'QT峰':<7} {'T偏移':<7}")
        print("-" * 80)
        for d in diag[:8]:
            t_qt = d["t_info"][0] if d["t_info"] else "-"
            t_off = d["t_info"][2] if d["t_info"] else "-"
            print(f"{d['r_time_ms']:<8} {d['r_amp']:<8.0f} {d['q10']*1000//SR:<6} {d['s10']*1000//SR:<6} {d['qrs10']:<7} {d['q5']*1000//SR:<6} {d['s5']*1000//SR:<6} {d['qrs5']:<7} {str(t_qt):<7} {str(t_off):<7}")
        print()


if __name__ == "__main__":
    main()
