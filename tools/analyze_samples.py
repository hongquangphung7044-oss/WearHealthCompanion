#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ECG 样本偏差检测工具（纯 Python，无 numpy 依赖）

用途：
1. 解析 samples/*.txt 诊断包（元信息 + 原始 ECG + HeartVoice API 返回 + 本地算法特征）
2. 用 Python 复现 EcgFeatureExtractor.kt 的关键算法（R 波检测 / QRS 宽度 / QT 间期）
3. 对照 HeartVoice API 返回，定位每个偏差的根因
4. 输出每一步的定位结果，辅助算法纠正

运行：python3 tools/analyze_samples.py
"""
import os
import sys
import math
import glob
import json
from statistics import median

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "..", "samples")
SAMPLE_RATE = 500


def parse_sample(path):
    """解析诊断包，返回 dict"""
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    lines = text.split("\n")
    info = {"path": path, "raw_ecg": [], "hv": {}, "local_feature_text": ""}
    section = None
    hv_start = -1
    feat_start = -1
    for i, line in enumerate(lines):
        if line.startswith("测量时间:"):
            info["time"] = line.split(":", 1)[1].strip()
        elif line.startswith("数据点数:"):
            info["points"] = int(line.split(":", 1)[1].strip())
        elif line.startswith("分析方法:"):
            info["method"] = line.split(":", 1)[1].strip()
        elif line.startswith("算法版本:"):
            info["algo_version"] = line.split(":", 1)[1].strip()
        elif line.startswith("[原始 ECG 数据]"):
            section = "raw"
        elif line.startswith("[HeartVoice API 返回]"):
            section = "hv"
            hv_start = i
        elif line.startswith("[算法提取后的结构化特征]"):
            section = "feat"
            feat_start = i
        elif line.startswith("[AI 解读]"):
            section = "ai"
        elif section == "raw":
            s = line.strip()
            if s and not s.startswith("#") and (s.lstrip("-").isdigit()):
                info["raw_ecg"].append(int(s))
        elif section == "hv":
            s = line.strip()
            if s.startswith("#") or not s:
                continue
            if ":" in s:
                k, v = s.split(":", 1)
                info["hv"][k.strip()] = v.strip()
        elif section == "feat":
            info["local_feature_text"] += line + "\n"
    return info


def parse_local_features(text):
    """从本地特征文本提取关键指标"""
    feat = {}
    for line in text.split("\n"):
        s = line.strip()
        if s.startswith("R波检测:"):
            # R波检测:26个 平均心率:56bpm 范围:53-69bpm
            parts = s.replace("R波检测:", "").split()
            if parts:
                feat["r_count"] = int(parts[0].replace("个", ""))
            for p in parts:
                if p.startswith("平均心率:"):
                    feat["local_hr"] = int(p.replace("平均心率:", "").replace("bpm", ""))
        elif s.startswith("RR间期序列"):
            # 提取 [*] 标记的异常间期
            rr_str = s.split(":", 1)[1] if ":" in s else ""
            rr_vals = []
            abnormal = 0
            for token in rr_str.replace("[", "").replace("]", "").replace("*", "").split():
                try:
                    v = int(token)
                    rr_vals.append(v)
                    if "*" in token or token.endswith("*"):
                        abnormal += 1
                except ValueError:
                    pass
            feat["rr_intervals"] = rr_vals
            feat["abnormal_rr"] = sum(1 for t in rr_str.split() if "*" in t)
        elif s.startswith("QRS宽度"):
            # QRS宽度(本地估测,ms):67±11
            v = s.split(":", 1)[1] if ":" in s else ""
            if "±" in v:
                a, b = v.split("±")
                feat["local_qrs"] = int(a)
                feat["local_qrs_std"] = int(b)
        elif s.startswith("QT间期"):
            # QT间期(本地估测,ms):392 QTc(Bazett):378ms
            v = s.split(":", 1)[1] if ":" in s else ""
            parts = v.split()
            for p in parts:
                if p.isdigit():
                    feat["local_qt"] = int(p)
                    break
            if "QTc(Bazett):" in s:
                qtc_part = s.split("QTc(Bazett):")[1].split("ms")[0]
                try:
                    feat["local_qtc"] = int(qtc_part)
                except ValueError:
                    pass
        elif s.startswith("短-长RR配对数"):
            v = s.split(":", 1)[1] if ":" in s else "0"
            feat["short_long_pairs"] = int("".join(c for c in v if c.isdigit()) or "0")
    return feat


# ===== Python 复现 EcgFeatureExtractor.kt 关键算法 =====

def detect_r_peaks(ecg, sample_rate=500):
    """
    复现 detectRPeaks：包络法 + 阈值 + 不应期
    简化版：用滑动窗口最大值作为包络，阈值 + 200ms 不应期 + 300ms 精修
    """
    n = len(ecg)
    if n < sample_rate:
        return []
    # 1. 去基线（滑动中位数，窗口 1000ms）
    win = sample_rate * 2 // 2  # 1s
    baseline = []
    for i in range(n):
        s = max(0, i - win // 2)
        e = min(n, i + win // 2 + 1)
        baseline.append(median(ecg[s:e]))
    # 2. 高通（去基线后）
    hp = [ecg[i] - baseline[i] for i in range(n)]
    # 3. 包络（滑动最大绝对值，窗口 80ms）
    env_win = sample_rate * 80 // 1000
    env = [0.0] * n
    for i in range(n):
        s = max(0, i - env_win // 2)
        e = min(n, i + env_win // 2 + 1)
        env[i] = max(abs(hp[j]) for j in range(s, e))
    # 4. 阈值（前 2s 学习期 80 分位）
    learn = env[:sample_rate * 2] if n >= sample_rate * 2 else env
    learn_sorted = sorted(learn)
    thr = learn_sorted[int(len(learn_sorted) * 0.8)] * 1.5
    # 5. 检测
    refractory = sample_rate // 5  # 200ms
    refine_refractory = int(sample_rate * 0.300)  # 300ms
    peaks = []
    last_peak = -refractory * 2
    i = 0
    while i < n:
        if env[i] < thr or (i - last_peak) < refractory:
            i += 1
            continue
        # 局部最大
        s = max(0, i - env_win)
        e = min(n, i + env_win + 1)
        best_idx = i
        best_val = 0
        for j in range(s, e):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        # 精修不应期 300ms
        if (best_idx - last_peak) >= refine_refractory:
            peaks.append(best_idx)
            last_peak = best_idx
        i = best_idx + refractory
    return peaks


def estimate_qrs_width(ecg, r_idx, sample_rate=500, threshold_ratio=0.10):
    """
    复现 QRS 宽度估测（阈值交叉法）
    返回 (qrs_ms, q_point, s_point, r_amplitude)
    """
    n = len(ecg)
    q_search_start = max(0, r_idx - sample_rate * 80 // 1000)
    s_search_end = min(n, r_idx + sample_rate * 80 // 1000)
    if s_search_end - q_search_start < 10:
        return None
    seg = ecg[q_search_start:s_search_end]
    seg_median = median(seg)
    # R 峰值（±10ms 邻域）
    refine_range = sample_rate // 50
    r_val = ecg[r_idx]
    for j in range(max(q_search_start, r_idx - refine_range), min(s_search_end, r_idx + refine_range + 1)):
        if abs(ecg[j] - seg_median) > abs(r_val - seg_median):
            r_val = ecg[j]
    r_amp = abs(r_val - seg_median)
    if r_amp < 30:
        return None
    threshold = r_amp * threshold_ratio
    # Q 点
    q_point = r_idx
    for i in range(r_idx, q_search_start - 1, -1):
        if abs(ecg[i] - seg_median) < threshold:
            q_point = i
            break
    # S 点
    s_point = r_idx
    for i in range(r_idx, s_search_end):
        if abs(ecg[i] - seg_median) < threshold:
            s_point = i
            break
    qrs_ms = (s_point - q_point) * 1000 // sample_rate
    return (qrs_ms, q_point, s_point, r_amp)


def estimate_qt_interval(ecg, r_idx, q_point, r_peaks, idx, sample_rate=500):
    """
    复现 QT 估测：找 T 波峰（绝对值最大极值点）
    返回 (qt_ms, t_peak_global, t_amplitude)
    """
    n = len(ecg)
    t_search_start = r_idx + sample_rate * 100 // 1000
    t_search_hard_end = r_idx + sample_rate * 500 // 1000
    next_r_limit = (r_peaks[idx + 1] - sample_rate * 50 // 1000) if (idx + 1 < len(r_peaks)) else n
    t_search_end = min(n, t_search_hard_end, next_r_limit)
    if t_search_end <= t_search_start + 10:
        return None
    t_seg = ecg[t_search_start:t_search_end]
    t_med = median(t_seg)
    # 平滑
    smooth_win = 5
    smoothed = []
    for i in range(len(t_seg)):
        s = max(0, i - smooth_win // 2)
        e = min(len(t_seg), i + smooth_win // 2 + 1)
        smoothed.append(sum(t_seg[s:e]) / (e - s) - t_med)
    # 找极值点
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
    t_peak_global = t_search_start + t_idx
    qt_ms = (t_peak_global - q_point) * 1000 // sample_rate
    return (qt_ms, t_peak_global, max_dev)


def analyze_sample(path):
    """分析单个样本"""
    info = parse_sample(path)
    if not info["raw_ecg"]:
        return None
    ecg = info["raw_ecg"]
    hv = info["hv"]
    local_feat = parse_local_features(info["local_feature_text"])

    result = {
        "file": os.path.basename(path),
        "time": info.get("time", "?"),
        "method": info.get("method", "?"),
        "version": info.get("algo_version", "(无版本号)"),
        "points": len(ecg),
        "hv_hr": int(hv.get("平均心率", "0") or "0"),
        "hv_qrs": int(hv.get("QRS宽度", "0") or "0"),
        "hv_qt": int(hv.get("QT间期", "0") or "0"),
        "hv_qtc": int(hv.get("QTc", "0") or "0"),
        "hv_pac": int(hv.get("房性早搏", "0") or "0"),
        "hv_pvc": int(hv.get("室性早搏", "0") or "0"),
        "hv_diag": hv.get("诊断", "?"),
        "local_hr": local_feat.get("local_hr", 0),
        "local_qrs": local_feat.get("local_qrs", 0),
        "local_qt": local_feat.get("local_qt", 0),
        "local_qtc": local_feat.get("local_qtc", 0),
        "local_r_count": local_feat.get("r_count", 0),
        "local_pairs": local_feat.get("short_long_pairs", 0),
    }

    # 用 Python 复现算法跑一遍
    peaks = detect_r_peaks(ecg)
    result["py_r_count"] = len(peaks)

    # QRS 宽度（多档阈值对比）
    qrs_vals_10 = []
    qrs_vals_25 = []
    qrs_vals_15 = []
    for idx, r in enumerate(peaks):
        res = estimate_qrs_width(ecg, r)
        if res:
            qrs_ms, qp, sp, r_amp = res
            if 40 <= qrs_ms <= 200:
                qrs_vals_10.append(qrs_ms)
        # 阈值 25%
        res25 = estimate_qrs_width(ecg, r, threshold_ratio=0.25)
        if res25:
            qms, qp, sp, ra = res25
            if 40 <= qms <= 200:
                qrs_vals_25.append(qms)
        # 阈值 15%
        res15 = estimate_qrs_width(ecg, r, threshold_ratio=0.15)
        if res15:
            qms, qp, sp, ra = res15
            if 40 <= qms <= 200:
                qrs_vals_15.append(qms)

    result["py_qrs_10pct"] = int(sum(qrs_vals_10) / len(qrs_vals_10)) if qrs_vals_10 else 0
    result["py_qrs_15pct"] = int(sum(qrs_vals_15) / len(qrs_vals_15)) if qrs_vals_15 else 0
    result["py_qrs_25pct"] = int(sum(qrs_vals_25) / len(qrs_vals_25)) if qrs_vals_25 else 0

    # QT 间期
    qt_vals = []
    for idx, r in enumerate(peaks):
        qres = estimate_qrs_width(ecg, r)
        if not qres:
            continue
        _, qp, _, _ = qres
        qtres = estimate_qt_interval(ecg, r, qp, peaks, idx)
        if qtres:
            qt_ms, t_peak, t_amp = qtres
            if 250 <= qt_ms <= 600:
                qt_vals.append(qt_ms)
    result["py_qt"] = int(sum(qt_vals) / len(qt_vals)) if qt_vals else 0

    # 偏差
    if result["hv_qrs"] > 0 and result["local_qrs"] > 0:
        result["qrs_diff"] = result["local_qrs"] - result["hv_qrs"]
    if result["hv_qt"] > 0 and result["local_qt"] > 0:
        result["qt_diff"] = result["local_qt"] - result["hv_qt"]
    if result["hv_qtc"] > 0 and result["local_qtc"] > 0:
        result["qtc_diff"] = result["local_qtc"] - result["hv_qtc"]
    if result["hv_hr"] > 0 and result["local_hr"] > 0:
        result["hr_diff"] = result["local_hr"] - result["hv_hr"]

    return result


def main():
    # 只分析带 HeartVoice API 返回的样本（heartvoice 方法）
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    results = []
    for f in files:
        info = parse_sample(f)
        if info.get("method") == "heartvoice" and info.get("hv"):
            r = analyze_sample(f)
            if r:
                results.append(r)

    print("=" * 100)
    print("ECG 样本偏差检测报告（HeartVoice API vs 本地算法 v7）")
    print("=" * 100)
    print(f"共分析 {len(results)} 份带 HeartVoice 对照的样本\n")

    # 表格输出
    print(f"{'文件':<28} {'HV心率':<7} {'本地心率':<8} {'HV_QRS':<7} {'本地QRS':<8} {'HV_QT':<6} {'本地QT':<7} {'HV_QTc':<7} {'本地QTc':<8} {'早搏配对':<8}")
    print("-" * 100)
    for r in results:
        print(f"{r['file']:<28} {r['hv_hr']:<7} {r['local_hr']:<8} {r['hv_qrs']:<7} {r['local_qrs']:<8} {r['hv_qt']:<6} {r['local_qt']:<7} {r['hv_qtc']:<7} {r['local_qtc']:<8} {r['local_pairs']:<8}")

    print("\n" + "=" * 100)
    print("偏差汇总（本地 - HeartVoice，负=偏小，正=偏大）")
    print("=" * 100)
    print(f"{'文件':<28} {'心率差':<8} {'QRS差':<8} {'QT差':<8} {'QTc差':<8}")
    print("-" * 60)
    for r in results:
        print(f"{r['file']:<28} {r.get('hr_diff','-'):<8} {r.get('qrs_diff','-'):<8} {r.get('qt_diff','-'):<8} {r.get('qtc_diff','-'):<8}")

    print("\n" + "=" * 100)
    print("QRS 宽度阈值对比实验（定位偏窄根因）")
    print("=" * 100)
    print("HeartVoice QRS 都在 98-100ms。本地用 10% 阈值偏窄，测试不同阈值的效果：")
    print(f"{'文件':<28} {'HV_QRS':<8} {'本地10%':<9} {'Py_10%':<8} {'Py_15%':<8} {'Py_25%':<8}")
    print("-" * 70)
    for r in results:
        print(f"{r['file']:<28} {r['hv_qrs']:<8} {r['local_qrs']:<9} {r['py_qrs_10pct']:<8} {r['py_qrs_15pct']:<8} {r['py_qrs_25pct']:<8}")

    print("\n" + "=" * 100)
    print("QT 间期对比（定位偏长根因）")
    print("=" * 100)
    print(f"{'文件':<28} {'HV_QT':<8} {'本地QT':<8} {'Py_QT':<8} {'QT差':<8}")
    print("-" * 60)
    for r in results:
        print(f"{r['file']:<28} {r['hv_qt']:<8} {r['local_qt']:<8} {r['py_qt']:<8} {r.get('qt_diff','-'):<8}")

    print("\n" + "=" * 100)
    print("早搏假阳性分析（HV 都判 SN=0早搏，本地都检出配对）")
    print("=" * 100)
    for r in results:
        print(f"{r['file']:<28} HV诊断={r['hv_diag']:<6} 房早={r['hv_pac']} 室早={r['hv_pvc']} | 本地短长配对={r['local_pairs']} 本地R波={r['local_r_count']} Py_R波={r['py_r_count']}")

    # 输出 JSON 供进一步分析
    json_path = os.path.join(os.path.dirname(__file__), "analysis_result.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\n详细结果已保存：{json_path}")


if __name__ == "__main__":
    main()
