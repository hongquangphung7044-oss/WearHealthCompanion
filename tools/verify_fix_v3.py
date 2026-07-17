#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证修复后的算法效果 v3（信号平均法 QT 估测）
- R 波：加严不应期到 400ms + T 波形态排除窗口 280-500ms + 振幅阈值 80%
- QRS：信噪比自适应法（SNR≥3 用 5% 阈值，SNR<3 用先验 100ms）
- QT：信号平均法（多周期叠加平均 → 切线法找 T 波终点）
  - 把所有心动周期按 R 峰对齐叠加平均，T 波 SNR 提升 sqrt(N) 倍
  - 在平均波形上用切线法（Lepeschkin 法）找 T 波终点
  - 依据：SAECG (Signal-Averaged ECG) 是临床标准做法（Breithardt 1991）
"""
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


def detect_r_peaks_fixed(ecg, sr=SR):
    """修复版 R 波检测：
    - 精修后不应期 360ms → 400ms
    - T 波排除窗口 300-450ms → 280-500ms
    - T 波振幅阈值 < 60% → < 80%
    """
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
        t = m + 1.7 * sd
        for i in range(ss, se):
            thr_arr[i] = t
    floor = 3.0
    refractory = sr // 5
    refine_refractory = int(sr * 0.400)
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
            if 280.0 <= offset_ms <= 500.0:
                prev_r_amp = abs(hp[peaks[-1]])
                curr_amp = abs(hp[best_idx])
                if curr_amp < prev_r_amp * 0.8:
                    continue
        peaks.append(best_idx)
        last_peak = best_idx
    # 回溯补检
    if len(peaks) >= 5:
        extra = []
        for k in range(1, len(peaks)):
            rr = peaks[k] - peaks[k - 1]
            rs, re = max(0, k - 4), min(len(peaks), k + 4)
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
                bb_idx, bb_val = -1, back_thr
                for j in range(ss, se + 1):
                    if j < 0 or j >= n:
                        continue
                    if env[j] > bb_val:
                        blo, bhi = max(0, j - check_range), min(n, j + check_range + 1)
                        ism = True
                        for m in range(blo, bhi):
                            if m != j and env[m] > env[j]:
                                ism = False
                                break
                        if ism:
                            bb_val = env[j]
                            bb_idx = j
                if bb_idx >= 0:
                    r_lo, r_hi = max(0, bb_idx - sr // 20), min(n, bb_idx + sr // 20)
                    r_idx, r_val = bb_idx, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    prev_r = max([p for p in peaks if p < r_idx] + [-refractory * 4])
                    offset_ms = (r_idx - prev_r) * 1000.0 / sr
                    if 280.0 <= offset_ms <= 500.0:
                        prev_r_amp = abs(hp[prev_r])
                        curr_amp = abs(hp[r_idx])
                        if curr_amp < prev_r_amp * 0.8:
                            continue
                    if any(abs(r_idx - p) < refine_refractory for p in peaks):
                        continue
                    extra.append(r_idx)
        if extra:
            peaks.extend(extra)
            peaks.sort()
    return peaks


def estimate_qrs_snr_adaptive(ecg, r_idx, sr=SR):
    """QRS：信噪比自适应法（保留）"""
    n = len(ecg)
    q_start = max(0, r_idx - sr * 100 // 1000)
    s_end = min(n, r_idx + sr * 100 // 1000)
    if s_end - q_start < 10:
        return None
    seg = ecg[q_start:s_end]
    seg_med = median(seg)
    rr = sr // 50
    r_val = ecg[r_idx]
    for j in range(max(q_start, r_idx - rr), min(s_end, r_idx + rr + 1)):
        if abs(ecg[j] - seg_med) > abs(r_val - seg_med):
            r_val = ecg[j]
    r_amp = abs(r_val - seg_med)
    if r_amp < 30:
        return None
    noise_segs = []
    for i in range(q_start, r_idx - sr * 80 // 1000):
        noise_segs.append(ecg[i] - seg_med)
    for i in range(r_idx + sr * 80 // 1000, s_end):
        noise_segs.append(ecg[i] - seg_med)
    if not noise_segs:
        noise_segs = [ecg[i] - seg_med for i in range(q_start, s_end)]
    noise_rms = (sum(x * x for x in noise_segs) / len(noise_segs)) ** 0.5
    snr = r_amp / noise_rms if noise_rms > 0 else 0
    if snr < 3.0:
        q_point = max(0, r_idx - sr * 50 // 1000)
        s_point = min(n, r_idx + sr * 50 // 1000)
        return (100, q_point, s_point, r_amp, snr)
    threshold = r_amp * 0.05
    q_point = r_idx
    for i in range(r_idx, q_start - 1, -1):
        if abs(ecg[i] - seg_med) < threshold:
            q_point = i
            break
    s_point = r_idx
    for i in range(r_idx, s_end):
        if abs(ecg[i] - seg_med) < threshold:
            s_point = i
            break
    qrs_ms = (s_point - q_point) * 1000 // sr
    return (qrs_ms, q_point, s_point, r_amp, snr)


def estimate_qt_signal_averaged(ecg, r_peaks, sr=SR):
    """QT：信号平均法
    1. 把所有心动周期按 R 峰对齐叠加平均 → 平均波形（T 波 SNR 提升 sqrt(N) 倍）
    2. 在平均波形上找 T 波峰
    3. 用切线法（Lepeschkin 法）找 T 波终点
    4. QT = T 波终点 - Q 点（Q 点用所有 QRS 的平均 Q 点）

    依据：SAECG (Signal-Averaged ECG) 是临床标准做法（Breithardt 1991）。
    信号平均能让 T 波从噪声中显现，单周期看不清的 T 波在 N=30 周期平均后
    SNR 提升 ~5.5 倍（sqrt(30)），切线法才能稳定工作。

    非硬编码补正：T 波终点由平均波形的切线交点决定，由信号本身决定，
    不是用先验值或固定补正。
    """
    n = len(ecg)
    if len(r_peaks) < 5:
        return None
    # 每周期窗口：R峰前 80ms（含 Q 点）到 R峰后 450ms（含 T 波）
    # 上限 450ms：覆盖正常心率下整个 T 波，HR=70 时 RR≈857ms，T 波到 R+450ms 内
    win_before = sr * 80 // 1000
    win_after = sr * 450 // 1000
    win_len = win_before + win_after
    # 收集每个 R 峰对应的周期段（避免越界）
    cycles = []
    for r_idx in r_peaks:
        start = r_idx - win_before
        end = r_idx + win_after
        if start < 0 or end >= n:
            continue
        # 限制不超过下一个 R 峰前 50ms（避免跨入下一心动周期）
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
    # 对齐平均：每段截取相同长度（取最短长度保证对齐）
    min_len = min(e - s for s, e in cycles)
    if min_len < sr * 300 // 1000:  # 至少 300ms 才能覆盖 T 波
        return None
    avg = [0.0] * min_len
    for s, e in cycles:
        L = e - s
        if L < min_len:
            L = min_len
        for i in range(min_len):
            avg[i] += ecg[s + i]
    avg = [x / len(cycles) for x in avg]
    # 去基线：用窗口前 30ms（R峰前 80ms 到 R峰前 50ms，即 PQ 段）作为基线
    bl_start = 0  # 窗口起点 = R峰前 80ms
    bl_end = sr * 30 // 1000  # 30ms
    if bl_end > min_len:
        bl_end = min_len // 4
    baseline = sum(avg[bl_start:bl_end]) / (bl_end - bl_start) if bl_end > bl_start else 0.0
    avg_hp = [x - baseline for x in avg]
    # R 峰位置在 win_before（窗口起点 = R-80ms，所以 R 在 index=win_before）
    r_pos_in_win = win_before
    # 找 Q 点：R 峰前第一个幅度 < R 振幅 5% 的点
    r_val = avg_hp[r_pos_in_win]
    # 实际 R 峰位置可能精修过，在 ±10ms 找最大绝对值
    for j in range(max(0, r_pos_in_win - sr // 50), min(min_len, r_pos_in_win + sr // 50 + 1)):
        if abs(avg_hp[j]) > abs(r_val):
            r_val = avg_hp[j]
    r_amp = abs(r_val)
    if r_amp < 30:
        return None
    # Q 点：5% 阈值（与 QRS 估测保持一致）
    q_threshold = r_amp * 0.05
    q_pos_in_win = r_pos_in_win
    for i in range(r_pos_in_win, max(0, r_pos_in_win - sr * 80 // 1000) - 1, -1):
        if abs(avg_hp[i]) < q_threshold:
            q_pos_in_win = i
            break
    # T 波搜索窗口：R+100ms 到 R+450ms（在平均波形上可以放宽到 450ms）
    t_start_in_win = r_pos_in_win + sr * 100 // 1000
    t_end_in_win = min(min_len, r_pos_in_win + sr * 450 // 1000)
    if t_end_in_win <= t_start_in_win + 20:
        return None
    # 平滑
    sw = 7  # 14ms 平滑（在平均波形上已经较平滑，平滑窗口可以小一点）
    smoothed = []
    for i in range(t_end_in_win - t_start_in_win):
        s, e = max(0, i - sw // 2), min(t_end_in_win - t_start_in_win, i + sw // 2 + 1)
        smoothed.append(sum(avg_hp[t_start_in_win + j] for j in range(s, e)) / (e - s))
    # 找 T 波峰（绝对值最大极值点）
    t_idx = -1
    max_dev = 0.0
    for i in range(1, len(smoothed) - 1):
        is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                  (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
        if is_peak and abs(smoothed[i]) > max_dev:
            max_dev = abs(smoothed[i])
            t_idx = i
    if t_idx < 0:
        return None
    # 估算 T 波 SNR：T 峰振幅 / 噪声 RMS
    noise_seg = [smoothed[i] for i in range(len(smoothed)) if abs(i - t_idx) > sr * 30 // 1000]
    if not noise_seg:
        noise_seg = smoothed[:]
    noise_rms = (sum(x * x for x in noise_seg) / len(noise_seg)) ** 0.5
    t_snr = max_dev / noise_rms if noise_rms > 0 else 0
    # 切线法找 T 波终点
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
    t_end_in_win = t_start_in_win + t_end_idx
    qt_ms = (t_end_in_win - q_pos_in_win) * 1000 // sr
    return (qt_ms, t_snr, max_dev, len(cycles))


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    print("=" * 130)
    print("修复版算法验证报告 v3（信号平均法 QT）")
    print("=" * 130)
    print(f"{'文件':<32} {'HV_HR':<6} {'HV_QRS':<7} {'新QRS':<7} {'QRS差':<7} {'HV_QT':<6} {'新QT':<6} {'QT差':<6} {'T_SNR':<7} {'N周期':<6} {'HV_QTc':<7} {'新QTc':<7} {'R波':<5} {'短RR':<5}")
    print("-" * 130)
    sum_qrs_diff = 0
    sum_qt_diff = 0
    n_samples = 0
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_hr = int(hv.get("平均心率", "0") or "0")
        hv_qrs = int(hv.get("QRS宽度", "0") or "0")
        hv_qt = int(hv.get("QT间期", "0") or "0")
        hv_qtc = int(hv.get("QTc", "0") or "0")
        if hv_qrs == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        if len(peaks) >= 3:
            rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
            valid_rr = [r for r in rr_list if 300 <= r <= 2500]
            if valid_rr:
                local_hr = 60000 // (sum(valid_rr) // len(valid_rr))
            else:
                local_hr = hv_hr
        else:
            local_hr = hv_hr
        # QRS
        qrs_vals = []
        for r in peaks:
            res = estimate_qrs_snr_adaptive(ecg, r)
            if res:
                qms = res[0]
                if 40 <= qms <= 200:
                    qrs_vals.append(qms)
        new_qrs = sum(qrs_vals) // len(qrs_vals) if qrs_vals else 0
        # QT (信号平均法)
        qt_res = estimate_qt_signal_averaged(ecg, peaks)
        if qt_res:
            new_qt, t_snr, t_amp, n_cycles = qt_res
        else:
            new_qt, t_snr, n_cycles = 0, 0, 0
        # 短 RR 统计
        short_rr = 0
        for i in range(1, len(peaks)):
            rr = (peaks[i] - peaks[i - 1]) * 1000 // SR
            if 300 <= rr <= 500:
                short_rr += 1
        # QTc
        rr_sec = 60.0 / hv_hr if hv_hr > 0 else 0
        new_qtc = int(new_qt / (rr_sec ** 0.5)) if (new_qt > 0 and rr_sec > 0) else 0
        qrs_diff = new_qrs - hv_qrs
        qt_diff = new_qt - hv_qt
        sum_qrs_diff += abs(qrs_diff)
        sum_qt_diff += abs(qt_diff)
        n_samples += 1
        print(f"{os.path.basename(f):<32} {hv_hr:<6} {hv_qrs:<7} {new_qrs:<7} {qrs_diff:<+7} {hv_qt:<6} {new_qt:<6} {qt_diff:<+6} {t_snr:<7.2f} {n_cycles:<6} {hv_qtc:<7} {new_qtc:<7} {len(peaks):<5} {short_rr:<5}")
    print()
    if n_samples:
        print(f"平均绝对偏差：QRS={sum_qrs_diff/n_samples:.1f}ms, QT={sum_qt_diff/n_samples:.1f}ms")
    print("目标：QRS差 ±10ms 内，QT差 ±15ms 内，短RR 接近 0")


if __name__ == "__main__":
    main()
