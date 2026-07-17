"""
验证 RPeakReliability 评估逻辑：复现 computeRPeakReliability，在所有真实样本上测试。

预期：
- 223211（导联反接+基线漂移，曾误判房颤）：可靠性=不可信
- 223747 / 运动后 / 静息 / 活动后 / 静息旧（正常窦性）：可靠性=可信 或 边缘

复现要点：
- rawRPeaks = detectRPeaks 原始输出（未剔噪声段）
- effectiveRPeaks = rawRPeaks 剔除落在噪声段（rms<0.10 段）内的 R 波
- rrIntervals = 由 effectiveRPeaks 算的 RR（已过 300-2500ms）
- avgHr = 由 filterEctopicBeats(rrIntervals) 后的心率（computeHeartRateStats）
- segments = 用 effectiveRPeaks 重算的逐秒分段
"""
import math
import re
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import (
    load_ecg, compute_envelope, detect_v5, filter_ectopic
)


def compute_rr(r_peaks, sr=500):
    """复现 computeRRIntervals：300-2500ms 区间过滤"""
    rr = []
    for i in range(1, len(r_peaks)):
        rr_ms = int((r_peaks[i] - r_peaks[i - 1]) * 1000.0 / sr)
        if 300 <= rr_ms <= 2500:
            rr.append(rr_ms)
    return rr


def compute_hr(rr_for_hrv):
    """复现 computeHeartRateStats 简化版（avgHr）。
    Kotlin 实现用剔早搏 RR 的中位数附近统计，这里用平均 RR 反推心率近似。
    真实实现见 EcgFeatureExtractor.computeHeartRateStats，这里只为验证 reliability 一致性。"""
    if not rr_for_hrv:
        return 0
    # 用中位数（更抗误检）
    sorted_rr = sorted(rr_for_hrv)
    median_rr = sorted_rr[len(sorted_rr) // 2]
    if median_rr <= 0:
        return 0
    return int(60000.0 / median_rr)


def extract_segments(ecg, r_peaks, sr=500):
    """复现 extractSegments：1 秒一段，返回每段 (rPeakCount, rmsMv)"""
    seg_len = sr
    seg_count = max(1, len(ecg) // seg_len)
    segments = []
    for seg_idx in range(seg_count):
        start = seg_idx * seg_len
        end = min(len(ecg), (seg_idx + 1) * seg_len)
        if end - start < sr // 2:
            continue
        seg = ecg[start:end]
        seg_mean = sum(seg) / len(seg)
        centered = [(x - seg_mean) / 1000.0 for x in seg]  # mV
        rms = math.sqrt(sum(c * c for c in centered) / len(centered))
        r_count = sum(1 for p in r_peaks if start <= p < end)
        segments.append({"r_count": r_count, "rms": rms})
    return segments


def filter_noise_r_peaks(r_peaks, segments, sr=500):
    """复现噪声段剔除：rms<0.10 的段内的 R 波剔除"""
    noise_ranges = []
    seg_len = sr
    for seg_idx, seg in enumerate(segments):
        if seg["rms"] < 0.10:
            start_sample = seg_idx * seg_len
            noise_ranges.append((start_sample, start_sample + seg_len))
    effective = []
    for p in r_peaks:
        in_noise = any(s <= p < e for s, e in noise_ranges)
        if not in_noise:
            effective.append(p)
    return effective if effective else r_peaks


def compute_reliability(raw_r_peaks, effective_r_peaks, rr_intervals, avg_hr, duration_sec, segments, sr=500):
    """复现 computeRPeakReliability"""
    if len(raw_r_peaks) < 4 or duration_sec < 5.0:
        return {
            "reliable": False, "level": "不可信",
            "reason": f"R 波数不足({len(raw_r_peaks)})或时长过短({duration_sec}s)",
            "extreme": 0, "valid": 1, "jump": 0, "consistency": 1, "abnormal_seg": 0,
        }

    # 1. 极端/有效 RR 占比（用 rawRPeaks）
    total_pairs = len(raw_r_peaks) - 1
    extreme_count = 0
    valid_count = 0
    for i in range(1, len(raw_r_peaks)):
        rr_ms = int((raw_r_peaks[i] - raw_r_peaks[i - 1]) * 1000.0 / sr)
        if 300 <= rr_ms <= 2500:
            valid_count += 1
        else:
            extreme_count += 1
    extreme_ratio = extreme_count / total_pairs if total_pairs > 0 else 0
    valid_ratio = valid_count / total_pairs if total_pairs > 0 else 1

    # 2. 相邻 RR 跳变比例（用清洗后 RR，>40% mean 视为跳变）
    # 用清洗后 RR 而非原始 RR：原始 RR 含误检/早搏导致跳变普遍偏高（30-60%），无区分度；
    # 清洗后 RR 的跳变反映 filterEctopicBeats 救不了的残余剧烈跳变，更能区分"真不稳定"
    rr_clean_for_jump = filter_ectopic(rr_intervals)
    if len(rr_clean_for_jump) >= 2 and sum(rr_clean_for_jump) > 0:
        mean_c = sum(rr_clean_for_jump) / len(rr_clean_for_jump)
        jump_count = sum(1 for k in range(1, len(rr_clean_for_jump))
                         if abs(rr_clean_for_jump[k] - rr_clean_for_jump[k - 1]) / mean_c > 0.40)
        jump_ratio = jump_count / (len(rr_clean_for_jump) - 1)
    else:
        jump_ratio = 0

    # 3. R 峰-时长一致性
    expected = max(1.0, duration_sec * avg_hr / 60.0) if avg_hr > 0 else 1.0
    consistency = len(effective_r_peaks) / expected

    # 4. 异常段占比
    abnormal_count = sum(1 for s in segments if s["r_count"] == 0 or s["rms"] < 0.10)
    abnormal_ratio = abnormal_count / len(segments) if segments else 1

    # 综合判定：不可信
    # 阈值校准依据：5 份真实样本
    # - 223211(导联反接+漂移，曾误判房颤): 极端21% → 不可信 ✓
    # - 223747: 极端31% → 不可信 ✓
    # - 运动后/静息/活动后/静息旧: 极端3-14% → 可信/边缘 ✓
    reasons = []
    if extreme_ratio > 0.18:
        reasons.append(f"极端RR占比{extreme_ratio * 100:.0f}%")
    if valid_ratio < 0.80:
        reasons.append(f"有效RR占比{valid_ratio * 100:.0f}%")
    if jump_ratio > 0.20:
        reasons.append(f"相邻RR跳变(清洗后){jump_ratio * 100:.0f}%")
    if consistency < 0.60:
        reasons.append(f"R峰数偏少(一致性{consistency:.2f})")
    if consistency > 1.50:
        reasons.append(f"R峰数偏多(一致性{consistency:.2f})")
    if abnormal_ratio > 0.50:
        reasons.append(f"异常段占比{abnormal_ratio * 100:.0f}%")
    if reasons:
        return {
            "reliable": False, "level": "不可信",
            "reason": "R波定位稳定性不足：" + "、".join(reasons),
            "extreme": extreme_ratio, "valid": valid_ratio, "jump": jump_ratio,
            "consistency": consistency, "abnormal_seg": abnormal_ratio,
        }

    # 边缘判定
    edge_reasons = []
    if extreme_ratio > 0.08:
        edge_reasons.append(f"极端RR占比{extreme_ratio * 100:.0f}%")
    if valid_ratio < 0.90:
        edge_reasons.append(f"有效RR占比{valid_ratio * 100:.0f}%")
    if jump_ratio > 0.10:
        edge_reasons.append(f"相邻RR跳变(清洗后){jump_ratio * 100:.0f}%")
    if consistency < 0.75:
        edge_reasons.append(f"R峰数偏少(一致性{consistency:.2f})")
    if consistency > 1.35:
        edge_reasons.append(f"R峰数偏多(一致性{consistency:.2f})")
    if abnormal_ratio > 0.30:
        edge_reasons.append(f"异常段占比{abnormal_ratio * 100:.0f}%")
    if edge_reasons:
        return {
            "reliable": True, "level": "边缘",
            "reason": "R波定位稳定性边缘：" + "、".join(edge_reasons),
            "extreme": extreme_ratio, "valid": valid_ratio, "jump": jump_ratio,
            "consistency": consistency, "abnormal_seg": abnormal_ratio,
        }

    return {
        "reliable": True, "level": "可信", "reason": "",
        "extreme": extreme_ratio, "valid": valid_ratio, "jump": jump_ratio,
        "consistency": consistency, "abnormal_seg": abnormal_ratio,
    }


def main():
    test_files = [
        ("223211(反接,HV78,SN)", "samples/ECG_diagnostic_20260716_223211.txt", "SN 窦性(曾误判房颤)"),
        ("223747(DS路径)", "samples/ECG_diagnostic_20260716_223747.txt", "?"),
        ("运动后(HV107,SNT)", "samples/运动后.txt", "SNT 窦性心动过速"),
        ("静息(HV54,SNB)", "samples/静息.txt", "SNB 窦性心动过缓"),
        ("活动后(HV72,SN)", "samples/心电api活动后.txt", "SN 窦性"),
        ("静息旧(HV59,SNB)", "samples/心电api静息.txt", "SNB 窦性心动过缓"),
    ]

    print("=" * 140)
    print("RPeakReliability 验证：223211 应判不可信，正常窦性应判可信/边缘")
    print("=" * 140)
    print(f"{'样本':<24} {'HV诊断':<22} | {'R峰数':>5} {'有效R峰':>6} | "
          f"{'极端%':>5} {'有效%':>5} {'跳变%':>5} {'一致性':>6} {'异常段%':>6} | {'可靠性':<6} {'原因'}")
    print("-" * 140)

    for label, path, hv_diag in test_files:
        if not os.path.exists(path):
            print(f"{label:<24} [文件不存在: {path}]")
            continue
        ecg = load_ecg(path)
        sr = 500
        duration = len(ecg) / sr
        env, hp = compute_envelope(ecg, sr)
        raw_r_peaks = detect_v5(ecg, env, hp, sr=sr)
        # 噪声段剔除
        seg_raw = extract_segments(ecg, raw_r_peaks, sr)
        effective = filter_noise_r_peaks(raw_r_peaks, seg_raw, sr)
        rr = compute_rr(effective, sr)
        rr_for_hrv = filter_ectopic(rr)
        avg_hr = compute_hr(rr_for_hrv)
        # 重算 segments 用 effectiveRPeaks（与 Kotlin 一致）
        seg = extract_segments(ecg, effective, sr)
        rel = compute_reliability(raw_r_peaks, effective, rr, avg_hr, duration, seg, sr)
        # 早搏剔除比例（参考信息）
        ectopic_ratio = 1 - len(rr_for_hrv) / len(rr) if rr else 0
        print(f"{label:<24} {hv_diag:<22} | R峰{len(raw_r_peaks):>3} 有效{len(effective):>3} | "
              f"极端{rel['extreme'] * 100:>2.0f}% 有效{rel['valid'] * 100:>2.0f}% "
              f"净跳变{rel['jump'] * 100:>2.0f}% 剔早搏{ectopic_ratio*100:>2.0f}% "
              f"一致{rel['consistency']:.2f} 异常段{rel['abnormal_seg'] * 100:>2.0f}% | "
              f"{rel['level']:<4} {rel['reason']}")

    print()
    print("预期：223211→不可信(降级输出，不判房颤)；其他→可信/边缘(正常输出节律判读)")


if __name__ == "__main__":
    main()
