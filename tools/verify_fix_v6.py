#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证修复后的算法效果 v6（模板匹配 + 置信度分级）

核心思路（用户选择的"模板匹配+置信度分级"方向）：
- 不依赖单周期 T 波可见性（T 波振幅 <0.05mV 被噪声淹没）
- 用形态先验（用户自己的高质量周期做模板）约束测量
- 按模板 T 波 SNR 分三档输出：
  - 高置信（SNR>3）：模板上切线法实测
  - 中置信（1.5<SNR≤3）：贝叶斯估计 = 实测×0.5 + Bazett先验×0.5
  - 低置信（SNR≤1.5）：纯 Bazett 先验

改进点（相对 v4/v5）：
1. 高质量模板：只选 R 波振幅/噪声 RMS 最高的前 50% 周期做平均
   根因：v4/v5 用所有周期平均，漏检导致的合并周期拉低模板质量
2. 基线：R 峰前 PR 段（R-80ms 到 R-50ms 的 30ms 均值）
3. Q 点：用 QRS 宽度（R 峰前 QRS 宽度一半）
4. 切线法：无范围限制（v4 思路，v5 限范围导致 124550 偏差 +149ms）
5. SNR 阈值 2.5（v4 思路，v5 降到 1.5 让低 SNR 样本走切线法更偏）
6. 新增中置信档：贝叶斯估计，平滑过渡

QRS 修复保留 v4 的 SNR 自适应法（已验证有效，平均偏差 9.6ms）
R 波检测保留 v4（400ms 不应期 + T 波排除 280-500ms + 80% 阈值）
"""
import os
import glob
from statistics import median

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")
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
    """R 波检测（同 v4）"""
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
    """QRS：信噪比自适应法（同 v4，已验证有效）"""
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


def _qt_prior_bazett(avg_hr, qtc_prior=400):
    """低信噪比兜底：Bazett 反向公式 QT = QTc × sqrt(RR)
    QTc=400ms 是健康成人正常范围 350-450ms 的中位数（AHA/ESC 标准）
    依据：Postema 2014 (PMID 24827793)；Bazett 1920 (Heart 7:353-370)
    """
    hr = avg_hr if avg_hr > 0 else 70
    rr_sec = 60.0 / hr
    qt_ms = int(qtc_prior * (rr_sec ** 0.5))
    return (qt_ms, 0.0, 0, "低置信")


def _cycle_quality_score(ecg, r_idx, next_r, sr=SR):
    """计算单个周期质量分 = R 波振幅 / 周期噪声 RMS
    用于选择高质量周期构建模板
    """
    n = len(ecg)
    win_start = max(0, r_idx - sr * 80 // 1000)
    win_end = min(n, r_idx + sr * 400 // 1000)
    if next_r is not None:
        win_end = min(win_end, next_r - sr * 50 // 1000)
    if win_end - win_start < sr * 300 // 1000:
        return 0.0
    seg = ecg[win_start:win_end]
    seg_med = median(seg)
    # R 波振幅
    r_lo = max(win_start, r_idx - sr // 50)
    r_hi = min(win_end, r_idx + sr // 50 + 1)
    r_val = ecg[r_idx]
    for j in range(r_lo, r_hi):
        if abs(ecg[j] - seg_med) > abs(r_val - seg_med):
            r_val = ecg[j]
    r_amp = abs(r_val - seg_med)
    if r_amp < 20:
        return 0.0
    # 噪声 RMS（R 峰 ±80ms 之外）
    noise_segs = []
    for i in range(win_start, r_idx - sr * 80 // 1000):
        noise_segs.append(ecg[i] - seg_med)
    for i in range(r_idx + sr * 80 // 1000, win_end):
        noise_segs.append(ecg[i] - seg_med)
    if not noise_segs:
        return 0.0
    noise_rms = (sum(x * x for x in noise_segs) / len(noise_segs)) ** 0.5
    if noise_rms <= 0:
        return 0.0
    return r_amp / noise_rms


def estimate_qt_template_matched(ecg, r_peaks, avg_hr, qrs_width_ms, sr=SR):
    """QT：模板匹配 + 置信度分级
    1. 计算每个周期质量分，选前 50% 高质量周期构建模板
    2. 在模板上用切线法测 T 终（模板 SNR 高，切线法可靠）
    3. 按模板 T 波 SNR 分三档输出
    """
    n = len(ecg)
    if len(r_peaks) < 5:
        return _qt_prior_bazett(avg_hr)
    win_before = sr * 80 // 1000  # 40 samples = 80ms
    win_after = sr * 450 // 1000  # 225 samples = 450ms
    win_len = win_before + win_after

    # 先算 RR 中位数，用于排除异常 RR 周期（漏检导致的长 RR / 早搏导致的短 RR）
    rr_list = []
    for i in range(1, len(r_peaks)):
        rr_ms = (r_peaks[i] - r_peaks[i-1]) * 1000 // sr
        if 300 <= rr_ms <= 2500:
            rr_list.append(rr_ms)
    if not rr_list:
        return _qt_prior_bazett(avg_hr)
    rr_list_sorted = sorted(rr_list)
    rr_median = rr_list_sorted[len(rr_list_sorted) // 2]

    # 收集所有有效周期 + 质量分
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
        # 排除 RR 异常周期：只保留 RR 在中位数 0.8-1.2 倍范围内的周期
        # 根因：漏检导致的长 RR 周期，T 波被下一个 P 波干扰 → T 峰定位在 R+380-440ms（偏后 100-160ms）
        # 早搏导致的短 RR 周期，T 波形态异常
        curr_rr = (next_r - r_idx) * 1000 // sr if next_r is not None else rr_median
        if not (rr_median * 0.8 <= curr_rr <= rr_median * 1.2):
            continue
        q_score = _cycle_quality_score(ecg, r_idx, next_r, sr)
        if q_score > 0:
            cycles_with_q.append((start, end, q_score))

    if len(cycles_with_q) < 5:
        return _qt_prior_bazett(avg_hr)

    # 改进1：选质量分最高的前 50% 周期构建模板
    cycles_with_q.sort(key=lambda x: x[2], reverse=True)
    top_half = cycles_with_q[:max(5, len(cycles_with_q) // 2)]
    cycles = [(s, e) for s, e, _ in top_half]

    min_len = min(e - s for s, e in cycles)
    if min_len < sr * 350 // 1000:
        return _qt_prior_bazett(avg_hr)

    # 中位数平均（抗异常周期）
    n_cycles = len(cycles)
    avg = [0.0] * min_len
    for pos in range(min_len):
        vals = sorted(ecg[s + pos] for s, e in cycles)
        avg[pos] = vals[n_cycles // 2]

    # 基线：R 峰前 PR 段（窗口起点 R-80ms 到 R-50ms 的 30ms 均值）
    bl_start = 0
    bl_end = min(sr * 30 // 1000, win_before - sr * 10 // 1000)
    baseline = sum(avg[bl_start:bl_end]) / (bl_end - bl_start) if bl_end > bl_start else 0.0
    avg_hp = [x - baseline for x in avg]

    r_pos_in_win = win_before
    # 找 R 峰（±20ms 内 |振幅| 最大）
    r_val = avg_hp[r_pos_in_win]
    for j in range(max(0, r_pos_in_win - sr * 20 // 1000), min(min_len, r_pos_in_win + sr * 20 // 1000 + 1)):
        if abs(avg_hp[j]) > abs(r_val):
            r_val = avg_hp[j]
    r_amp = abs(r_val)
    if r_amp < 20:
        return _qt_prior_bazett(avg_hr)

    # Q 点：用 QRS 宽度（R 峰前 QRS 宽度一半）
    qrs_half_ms = (qrs_width_ms // 2) if qrs_width_ms > 0 else 50
    qrs_half_ms = max(20, min(80, qrs_half_ms))
    q_pos_in_win = r_pos_in_win - qrs_half_ms * sr // 1000
    q_pos_in_win = max(0, q_pos_in_win)

    # T 峰搜索：R+100ms 到 R+450ms
    t_start_in_win = r_pos_in_win + sr * 100 // 1000
    t_end_in_win = min(min_len, r_pos_in_win + sr * 450 // 1000)
    if t_end_in_win <= t_start_in_win + 10:
        return _qt_prior_bazett(avg_hr)

    # 平滑
    sw = 7
    smoothed = []
    for i in range(t_end_in_win - t_start_in_win):
        s, e = max(0, i - sw // 2), min(t_end_in_win - t_start_in_win, i + sw // 2 + 1)
        smoothed.append(sum(avg_hp[t_start_in_win + j] for j in range(s, e)) / (e - s))

    # 找 T 波峰
    t_idx = -1
    max_dev = 0.0
    for i in range(1, len(smoothed) - 1):
        is_peak = (smoothed[i] > smoothed[i - 1] and smoothed[i] >= smoothed[i + 1]) or \
                  (smoothed[i] < smoothed[i - 1] and smoothed[i] <= smoothed[i + 1])
        if is_peak and abs(smoothed[i]) > max_dev:
            max_dev = abs(smoothed[i])
            t_idx = i
    if t_idx < 0:
        return _qt_prior_bazett(avg_hr)

    # T 波 SNR
    noise_seg = [smoothed[i] for i in range(len(smoothed)) if abs(i - t_idx) > sr * 30 // 1000]
    if not noise_seg:
        noise_seg = smoothed[:]
    noise_rms = (sum(x * x for x in noise_seg) / len(noise_seg)) ** 0.5
    t_snr = max_dev / noise_rms if noise_rms > 0 else 0

    # 切线法（无范围限制，v4 思路）
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
    qt_measured = (t_end_in_win_global - q_pos_in_win) * 1000 // sr

    # 兜底先验
    qt_prior, _, _, _ = _qt_prior_bazett(avg_hr)

    # 置信度分级
    if t_snr > 3.0 and 250 <= qt_measured <= 500:
        # 高置信：实测
        return (qt_measured, t_snr, n_cycles, "高置信")
    elif t_snr > 1.5 and 250 <= qt_measured <= 500:
        # 中置信：贝叶斯估计 = 实测×0.5 + 先验×0.5
        qt_bayes = int(qt_measured * 0.5 + qt_prior * 0.5)
        return (qt_bayes, t_snr, n_cycles, "中置信")
    else:
        # 低置信：纯先验
        return (qt_prior, t_snr, n_cycles, "低置信")


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    print("=" * 140)
    print("修复版算法验证报告 v6（模板匹配 + 置信度分级）")
    print("=" * 140)
    print(f"{'文件':<32} {'HV_HR':<6} {'HV_QRS':<7} {'新QRS':<7} {'QRS差':<7} {'HV_QT':<6} {'新QT':<6} {'QT差':<6} {'T_SNR':<7} {'N周期':<6} {'置信度':<8} {'HV_QTc':<7} {'新QTc':<7} {'R波':<5} {'短RR':<5}")
    print("-" * 140)
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
                # 用 RR 中位数算心率（抗 R 波漏检导致的异常长 RR）
                # 根因：漏检让两个心跳合并成一个长 RR，均值被拉高 → local_hr 偏低
                # 中位数不受极端值影响，更接近真实心率
                valid_rr_sorted = sorted(valid_rr)
                rr_median = valid_rr_sorted[len(valid_rr_sorted) // 2]
                local_hr = 60000 // rr_median if rr_median > 0 else hv_hr
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
        # QT (模板匹配)
        qt_res = estimate_qt_template_matched(ecg, peaks, local_hr, new_qrs)
        if qt_res:
            new_qt, t_snr, n_cycles, confidence = qt_res
        else:
            new_qt, t_snr, n_cycles, confidence = 0, 0, 0, "?"
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
        print(f"{os.path.basename(f):<32} {hv_hr:<6} {hv_qrs:<7} {new_qrs:<7} {qrs_diff:<+7} {hv_qt:<6} {new_qt:<6} {qt_diff:<+6} {t_snr:<7.2f} {n_cycles:<6} {confidence:<8} {hv_qtc:<7} {new_qtc:<7} {len(peaks):<5} {short_rr:<5}")
    print()
    if n_samples:
        print(f"平均绝对偏差：QRS={sum_qrs_diff/n_samples:.1f}ms, QT={sum_qt_diff/n_samples:.1f}ms")
    print("目标：QRS差 ±10ms 内，QT差 ±15ms 内，短RR 接近 0")


if __name__ == "__main__":
    main()
