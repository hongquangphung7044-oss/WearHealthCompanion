"""
验证修复：精修二次不应期从 200ms 提高到 300ms。

根因：v6 的 200ms 不应期让 208-276ms 的 RR 通过，但这些仍被算极端 RR。
生理上 RR < 300ms = 心率 > 200bpm，腕表 ECG 不可能。

修复方案：
1. 主检测精修二次不应期：200ms → 300ms
2. 回溯补检 T 波排除窗口：200-450ms → 300-450ms（避免误杀真 R 峰）
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope, filter_ectopic, rhythm_features_fixed
from test_reliability import compute_reliability, extract_segments, filter_noise_r_peaks, compute_rr, compute_hr
from test_refine_fix import detect_v5_fixed as detect_v6_old


def detect_v6_300ms(ecg, env, hp, sr=500):
    """v6 但精修二次不应期用 300ms"""
    seg_len = sr * 4
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / len(seg)
        std = math.sqrt(var)
        thr = mean + std * 1.7
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5  # 200ms（envelope 不应期，不变）
    refine_refractory = int(sr * 0.300)  # 300ms（精修二次不应期，提高）
    check_range = sr // 50
    r_peaks = []
    last_r_peak = -refractory * 2

    for i in range(len(env)):
        thr = max(thresholds[i], 3.0)
        if env[i] < thr:
            continue
        lo, hi = max(0, i - check_range), min(len(env), i + check_range + 1)
        is_max = True
        for j in range(lo, hi):
            if j != i and env[j] >= env[i]:
                if j < i:
                    is_max = False
                    break
        if not is_max:
            continue
        if (i - last_r_peak) < refractory:
            continue
        r_lo, r_hi = max(0, i - sr // 20), min(len(hp), i + sr // 20)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        # 精修二次不应期：300ms
        if r_peaks and (best_idx - r_peaks[-1]) < refine_refractory:
            continue
        r_peaks.append(best_idx)
        last_r_peak = best_idx

    # 回溯补检
    if len(r_peaks) >= 5:
        extra = []
        for k in range(1, len(r_peaks)):
            rr = r_peaks[k] - r_peaks[k - 1]
            rs, re_ = max(0, k - 4), min(len(r_peaks), k + 4)
            recent = [r_peaks[m] - r_peaks[m - 1] for m in range(rs + 1, re_) if m != k]
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (r_peaks[k - 1] + r_peaks[k]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else 3.0
                back_thr = max(mid_thr, 3.0) * 0.5
                ss, se = r_peaks[k - 1] + refractory, r_peaks[k] - refractory
                if se <= ss:
                    continue
                best_b, best_bv = -1, back_thr
                for j in range(ss, se + 1):
                    if j < 0 or j >= len(env):
                        continue
                    if env[j] > best_bv:
                        bl, bh = max(0, j - check_range), min(len(env), j + check_range + 1)
                        is_m = True
                        for m in range(bl, bh):
                            if m != j and env[m] > env[j]:
                                is_m = False
                                break
                        if is_m:
                            best_bv = env[j]
                            best_b = j
                if best_b >= 0:
                    r_lo, r_hi = max(0, best_b - sr // 20), min(len(hp), best_b + sr // 20)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    if all(abs(r_idx - p) >= refine_refractory for p in r_peaks):
                        prev_r = max((p for p in r_peaks if p < r_idx), default=-99999)
                        offset_to_prev = (r_idx - prev_r) * 1000 / sr
                        # T 波窗口：300-450ms（避开真 R 峰）
                        if 300 <= offset_to_prev <= 450:
                            continue
                        extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def evaluate(peaks, ecg, sr, duration):
    if not peaks:
        return {"count": 0, "extreme": 1, "valid": 0, "cv": 0, "reliability": "不可信", "pattern": ""}
    seg_raw = extract_segments(ecg, peaks, sr)
    effective = filter_noise_r_peaks(peaks, seg_raw, sr)
    rr = compute_rr(effective, sr)
    rr_for_hrv = filter_ectopic(rr)
    avg_hr = compute_hr(rr_for_hrv)
    seg = extract_segments(ecg, effective, sr)
    rel = compute_reliability(peaks, effective, rr, avg_hr, duration, seg, sr)
    rhy = rhythm_features_fixed(rr)
    return {
        "count": len(peaks), "effective": len(effective),
        "extreme": rel["extreme"], "valid": rel["valid"],
        "cv": rhy["cv"], "pattern": rhy["pattern"], "reliability": rel["level"],
    }


def main():
    samples = [
        ("专业(HV,SNB,58bpm)", "samples/专业.txt", 30),
        ("ds均衡(80bpm)", "samples/ds均衡.txt", 41),
        ("dsmax(64bpm)", "samples/dsmax.txt", 33),
        # 旧样本回归测试
        ("223211(SN)", "samples/ECG_diagnostic_20260716_223211.txt", 40),
        ("运动后(SNT,107bpm)", "samples/运动后.txt", 55),
        ("静息(SNB,54bpm)", "samples/静息.txt", 28),
        ("心电api活动后(SN,72bpm)", "samples/心电api活动后.txt", 37),
    ]
    print("修复验证：精修二次不应期 200ms → 300ms")
    print(f"{'样本':<24} | {'版本':<6} | {'R峰':>4} {'有效':>4} {'极端%':>5} {'CV':>6} {'形态':<14} {'可靠性':<6}")
    print("-" * 100)
    for label, path, expected in samples:
        if not os.path.exists(path):
            continue
        ecg = load_ecg(path)
        sr = 500
        duration = len(ecg) / sr
        env, hp = compute_envelope(ecg, sr)
        r_old = detect_v6_old(ecg, env, hp, sr)
        e_old = evaluate(r_old, ecg, sr, duration)
        r_new = detect_v6_300ms(ecg, env, hp, sr)
        e_new = evaluate(r_new, ecg, sr, duration)
        print(f"{label:<24} | {'v6(200)':<6} | {e_old['count']:>4} {e_old['effective']:>4} "
              f"{e_old['extreme']*100:>5.0f} {e_old['cv']:>6.3f} {e_old['pattern']:<14} {e_old['reliability']:<6}")
        print(f"{'':<24} | {'v6(300)':<6} | {e_new['count']:>4} {e_new['effective']:>4} "
              f"{e_new['extreme']*100:>5.0f} {e_new['cv']:>6.3f} {e_new['pattern']:<14} {e_new['reliability']:<6}")
        print(f"{'':<24} | 期望R峰≈{expected}")


if __name__ == "__main__":
    main()
