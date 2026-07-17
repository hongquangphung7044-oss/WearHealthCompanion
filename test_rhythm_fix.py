"""
验证节律判别修复方案：CV/Poincaré 用 filterEctopicBeats 清洗后的 RR，
shortLongPairs 仍用原始 RR（保早搏检测）。

当前问题：节律判别用原始 RR，误检的短长 RR 拉高 CV→所有样本 CV 0.35-0.56→误判扇形/房颤
修复思路：CV 对误检敏感，用清洗后 RR；shortLongPairs 保早搏特征，用原始 RR

测试 5 个样本的 CV/ratio/pattern 变化
"""
import math
import re
import os


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def compute_envelope(ecg, sr=500):
    baseline = [0.0] * len(ecg)
    half_win = sr // 2
    for i in range(len(ecg)):
        lo, hi = max(0, i - half_win), min(len(ecg), i + half_win)
        baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
    hp = [ecg[i] - baseline[i] for i in range(len(ecg))]
    grad = [0.0] * len(ecg)
    for i in range(len(ecg)):
        prev, nxt = hp[max(0, i - 1)], hp[min(len(ecg) - 1, i + 1)]
        grad[i] = abs(nxt - prev) / 2.0
    sw = int(sr * 0.150)
    env = [0.0] * len(ecg)
    for i in range(len(ecg)):
        lo, hi = max(0, i - sw // 2), min(len(ecg), i + sw // 2 + 1)
        env[i] = sum(grad[lo:hi]) / (hi - lo)
    return env, hp


def detect_v5(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
    seg_len = sr * seg_sec
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / len(seg)
        std = math.sqrt(var)
        thr = mean + std * k
        for i in range(ss, se):
            thresholds[i] = thr
    refractory = sr // 5
    check_range = sr // 50
    refine_range = sr // 20
    r_peaks = []
    last_peak = -refractory * 2
    for i in range(len(env)):
        thr = max(thresholds[i], floor)
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
        if (i - last_peak) < refractory:
            continue
        r_lo, r_hi = max(0, i - refine_range), min(len(hp), i + refine_range + 1)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx
    if len(r_peaks) >= 5:
        extra = []
        for k_idx in range(1, len(r_peaks)):
            rr = r_peaks[k_idx] - r_peaks[k_idx - 1]
            rs, re_ = max(0, k_idx - 4), min(len(r_peaks), k_idx + 4)
            recent = [r_peaks[m] - r_peaks[m - 1] for m in range(rs + 1, re_) if m != k_idx]
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (r_peaks[k_idx - 1] + r_peaks[k_idx]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss, se = r_peaks[k_idx - 1] + refractory, r_peaks[k_idx] - refractory
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
                    r_lo, r_hi = max(0, best_b - refine_range), min(len(hp), best_b + refine_range + 1)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def filter_ectopic(rr, threshold=0.20):
    """复现 filterEctopicBeats：偏离均值>threshold 剔除"""
    if len(rr) < 3:
        return rr[:]
    mean = sum(rr) / len(rr)
    if mean <= 0:
        return rr[:]
    filtered = [x for x in rr if abs(x - mean) / mean <= threshold]
    return filtered if filtered else rr[:]


def rhythm_features(rr_raw):
    """复现 computeRhythmFeatures：CV + SD1/SD2 ratio + shortLongPairs + pattern"""
    if len(rr_raw) < 4:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    mean = sum(rr_raw) / len(rr_raw)
    if mean < 1:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    variance = sum((x - mean) ** 2 for x in rr_raw) / len(rr_raw)
    sdnn = math.sqrt(variance)
    cv = sdnn / mean
    # SD1/SD2
    diffs = [rr_raw[i] - rr_raw[i+1] for i in range(len(rr_raw)-1)]
    sums = [rr_raw[i] + rr_raw[i+1] for i in range(len(rr_raw)-1)]
    diff_mean = sum(diffs) / len(diffs)
    sum_mean = sum(sums) / len(sums)
    diff_var = sum((d - diff_mean) ** 2 for d in diffs) / len(diffs)
    sum_var = sum((s - sum_mean) ** 2 for s in sums) / len(sums)
    sd1 = math.sqrt(diff_var / 2.0)
    sd2 = math.sqrt(sum_var / 2.0)
    ratio = sd1 / sd2 if sd2 > 0.1 else 0.0
    # shortLongPairs
    slp = sum(1 for i in range(len(rr_raw)-1) if rr_raw[i] < mean*0.8 and rr_raw[i+1] > mean*1.2)
    # pattern
    if cv < 0.05:
        pattern = "彗星形(规律)"
    elif cv < 0.15 and ratio < 0.5:
        pattern = "彗星形(规律)"
    elif cv < 0.15 and ratio >= 0.5:
        pattern = "鱼雷形(轻度不齐)"
    elif 0.15 <= cv <= 0.20 and ratio >= 0.7:
        pattern = "扇形(不规律)"
    elif cv > 0.20 and ratio >= 0.7:
        pattern = "扇形(疑似房颤)❌"
    elif slp >= 3 and cv < 0.20:
        pattern = "复杂形(疑似早搏)"
    else:
        pattern = "彗星形(规律)"
    return {"cv": cv, "ratio": ratio, "slp": slp, "pattern": pattern}


def rhythm_features_fixed(rr_raw):
    """修复方案：CV/ratio 用 filterEctopicBeats 后的 RR，shortLongPairs 用原始 RR"""
    if len(rr_raw) < 4:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    rr_clean = filter_ectopic(rr_raw)
    if len(rr_clean) < 4:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    mean = sum(rr_clean) / len(rr_clean)
    if mean < 1:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    variance = sum((x - mean) ** 2 for x in rr_clean) / len(rr_clean)
    sdnn = math.sqrt(variance)
    cv = sdnn / mean
    diffs = [rr_clean[i] - rr_clean[i+1] for i in range(len(rr_clean)-1)]
    sums = [rr_clean[i] + rr_clean[i+1] for i in range(len(rr_clean)-1)]
    diff_mean = sum(diffs) / len(diffs)
    sum_mean = sum(sums) / len(sums)
    diff_var = sum((d - diff_mean) ** 2 for d in diffs) / len(diffs)
    sum_var = sum((s - sum_mean) ** 2 for s in sums) / len(sums)
    sd1 = math.sqrt(diff_var / 2.0)
    sd2 = math.sqrt(sum_var / 2.0)
    ratio = sd1 / sd2 if sd2 > 0.1 else 0.0
    # shortLongPairs 用原始 RR（保早搏）
    mean_raw = sum(rr_raw) / len(rr_raw)
    slp = sum(1 for i in range(len(rr_raw)-1) if rr_raw[i] < mean_raw*0.8 and rr_raw[i+1] > mean_raw*1.2)
    if cv < 0.05:
        pattern = "彗星形(规律)"
    elif cv < 0.15 and ratio < 0.5:
        pattern = "彗星形(规律)"
    elif cv < 0.15 and ratio >= 0.5:
        pattern = "鱼雷形(轻度不齐)"
    elif 0.15 <= cv <= 0.20 and ratio >= 0.7:
        pattern = "扇形(不规律)"
    elif cv > 0.20 and ratio >= 0.7:
        pattern = "扇形(疑似房颤)❌"
    elif slp >= 3 and cv < 0.20:
        pattern = "复杂形(疑似早搏)"
    else:
        pattern = "彗星形(规律)"
    return {"cv": cv, "ratio": ratio, "slp": slp, "pattern": pattern}


def main():
    test_files = [
        ("223211(反接,HV78,SN)", "samples/ECG_diagnostic_20260716_223211.txt", "SN 窦性"),
        ("223747(DS路径)", "samples/ECG_diagnostic_20260716_223747.txt", "?"),
        ("运动后(HV107,SNT)", "samples/运动后.txt", "SNT 窦性心动过速"),
        ("静息(HV54,SNB)", "samples/静息.txt", "SNB 窦性心动过缓"),
        ("活动后(HV72,SN)", "samples/心电api活动后.txt", "SN 窦性"),
        ("静息旧(HV59,SNB)", "samples/心电api静息.txt", "SNB 窦性心动过缓"),
    ]

    print("=" * 130)
    print("节律判别修复验证：CV/ratio 用清洗后 RR vs 原始 RR")
    print("当前(原始RR)→所有样本CV高误判扇形；修复(清洗RR)→CV下降，仅真异常判扇形")
    print("=" * 130)
    print(f"{'样本':<24} {'HV诊断':<16} | {'当前CV':>7} {'ratio':>6} {'SLP':>4} {'当前判读':<22} | "
          f"{'修复CV':>7} {'ratio':>6} {'SLP':>4} {'修复判读':<22}")
    print("-" * 130)

    for label, path, hv_diag in test_files:
        if not os.path.exists(path):
            continue
        ecg = load_ecg(path)
        env, hp = compute_envelope(ecg)
        r_peaks = detect_v5(ecg, env, hp)
        rr_raw = [int((r_peaks[i] - r_peaks[i-1]) * 1000 / 500) for i in range(1, len(r_peaks))]
        rr_raw = [x for x in rr_raw if 300 <= x <= 2500]
        cur = rhythm_features(rr_raw)
        fix = rhythm_features_fixed(rr_raw)
        print(f"{label:<24} {hv_diag:<16} | {cur['cv']:>7.3f} {cur['ratio']:>6.2f} {cur['slp']:>4} {cur['pattern']:<22} | "
              f"{fix['cv']:>7.3f} {fix['ratio']:>6.2f} {fix['slp']:>4} {fix['pattern']:<22}")

    print()
    print("说明：HV诊断均为窦性(SN/SNB/SNT)，无房颤。当前算法应判彗星/鱼雷，不应判扇形(房颤)")


if __name__ == "__main__":
    main()
