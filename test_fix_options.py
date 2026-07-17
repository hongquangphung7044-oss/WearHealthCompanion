"""
测试不同修复方案对导联反接+基线漂移样本的效果。
对比 4 个方案：
A. 当前 v5（1s 移动平均去基线）- 基线
B. 1s 中位数去基线（抗离群值，对 R 波尖峰不敏感）
C. v5 + 成簇过滤（<400ms 短 RR 簇只留 |hp| 最大）
D. 2s 移动平均去基线（更彻底去慢漂移）

在文件1(223211, HV 78bpm SN)上测试，目标：减少误检簇，CV 下降，R 波数接近 40
同时在其他样本(静息/活动后/运动后)上验证不回归
"""
import math
import re
import os


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def baseline_moving_avg(ecg, sr=500, win_sec=1.0):
    """移动平均去基线"""
    baseline = [0.0] * len(ecg)
    half_win = int(sr * win_sec / 2)
    for i in range(len(ecg)):
        lo, hi = max(0, i - half_win), min(len(ecg), i + half_win)
        baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
    return baseline


def baseline_median(ecg, sr=500, win_sec=1.0):
    """中位数去基线（抗 R 波尖峰，基线估计更准）"""
    baseline = [0.0] * len(ecg)
    half_win = int(sr * win_sec / 2)
    for i in range(len(ecg)):
        lo, hi = max(0, i - half_win), min(len(ecg), i + half_win)
        seg = sorted(ecg[lo:hi])
        baseline[i] = seg[len(seg) // 2]
    return baseline


def compute_envelope_with_baseline(ecg, baseline_func, sr=500, win_sec=1.0):
    """用指定去基线方法计算 envelope"""
    baseline = baseline_func(ecg, sr, win_sec)
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
    """v5 检测（k=1.7, ±50ms 精修, 1.66×回溯）"""
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


def filter_clusters(r_peaks, hp, sr=500, short_threshold_ms=400):
    """方案C：成簇过滤 - 连续 R 波间隔 <short_threshold_ms 的簇只保留 |hp| 最大的"""
    if len(r_peaks) < 2:
        return r_peaks
    # 分簇
    clusters = []
    current = [r_peaks[0]]
    for i in range(1, len(r_peaks)):
        rr_ms = (r_peaks[i] - r_peaks[i - 1]) * 1000 / sr
        if rr_ms < short_threshold_ms:
            current.append(r_peaks[i])
        else:
            clusters.append(current)
            current = [r_peaks[i]]
    clusters.append(current)
    # 每簇只留 |hp| 最大的
    filtered = []
    for cluster in clusters:
        if len(cluster) == 1:
            filtered.append(cluster[0])
        else:
            best = max(cluster, key=lambda idx: abs(hp[idx]))
            filtered.append(best)
    filtered.sort()
    return filtered


def compute_stats(r_peaks, sr=500):
    """计算 R 波数、有效 RR、异常率、CV"""
    if len(r_peaks) < 2:
        return {"n": len(r_peaks), "rr_valid": 0, "abnormal_pct": 0, "cv": 0, "short_rr": 0}
    rr = [(r_peaks[i] - r_peaks[i-1]) * 1000 / sr for i in range(1, len(r_peaks))]
    rr_valid = [x for x in rr if 300 <= x <= 2500]
    if not rr_valid:
        return {"n": len(r_peaks), "rr_valid": 0, "abnormal_pct": 0, "cv": 0, "short_rr": sum(1 for x in rr if x < 400)}
    mean_rr = sum(rr_valid) / len(rr_valid)
    abnormal = sum(1 for x in rr_valid if abs(x - mean_rr) / mean_rr > 0.20) if mean_rr > 0 else 0
    cv = math.sqrt(sum((x-mean_rr)**2 for x in rr_valid)/len(rr_valid)) / mean_rr if mean_rr > 0 else 0
    short_rr = sum(1 for x in rr if 0 < x < 400)
    return {
        "n": len(r_peaks),
        "rr_valid": len(rr_valid),
        "abnormal_pct": abnormal / len(rr_valid) * 100,
        "cv": cv,
        "short_rr": short_rr,
        "mean_rr": mean_rr,
    }


def run_scheme(label, ecg, baseline_func, win_sec=1.0, apply_cluster_filter=False, sr=500):
    """跑一个方案"""
    env, hp = compute_envelope_with_baseline(ecg, baseline_func, sr, win_sec)
    r_peaks = detect_v5(ecg, env, hp, sr=sr)
    if apply_cluster_filter:
        r_peaks = filter_clusters(r_peaks, hp, sr)
    stats = compute_stats(r_peaks, sr)
    return r_peaks, stats


def main():
    # 测试样本：文件1（导联反接+漂移，HV 78bpm SN 0早搏）
    test_files = [
        ("223211(反接,HV78)", "samples/ECG_diagnostic_20260716_223211.txt", 78, 40),
        ("223747(DS路径)", "samples/ECG_diagnostic_20260716_223747.txt", None, None),
        ("运动后(HV107)", "samples/运动后.txt", 107, 53),
        ("静息(HV54)", "samples/静息.txt", 54, 27),
        ("活动后(HV72)", "samples/心电api活动后.txt", 72, 36),
    ]

    schemes = [
        ("A. v5(1s均值)", lambda ecg: run_scheme("A", ecg, baseline_moving_avg, 1.0)),
        ("B. v5(1s中位数)", lambda ecg: run_scheme("B", ecg, baseline_median, 1.0)),
        ("C. v5+簇过滤", lambda ecg: run_scheme("C", ecg, baseline_moving_avg, 1.0, apply_cluster_filter=True)),
        ("D. v5(2s均值)", lambda ecg: run_scheme("D", ecg, baseline_moving_avg, 2.0)),
        ("E. 中位+簇过滤", lambda ecg: run_scheme("E", ecg, baseline_median, 1.0, apply_cluster_filter=True)),
    ]

    print("=" * 120)
    print("修复方案对比（目标：223211 误检簇减少 CV 下降；其他样本不回归）")
    print("=" * 120)
    print(f"{'样本':<22} {'HV':>5} {'方案':<18} {'R波':>5} {'有效RR':>6} {'异常%':>6} {'CV':>6} {'短RR':>5} {'meanRR':>7}")
    print("-" * 120)

    for label, path, hv_hr, expected_r in test_files:
        if not os.path.exists(path):
            continue
        ecg = load_ecg(path)
        hv_str = f"{hv_hr}" if hv_hr else "?"
        for scheme_name, scheme_func in schemes:
            r_peaks, stats = scheme_func(ecg)
            print(f"{label:<22} {hv_str:>5} {scheme_name:<18} {stats['n']:>5} {stats['rr_valid']:>6} "
                  f"{stats['abnormal_pct']:>5.0f}% {stats['cv']:>6.3f} {stats['short_rr']:>5} {stats.get('mean_rr',0):>7.0f}")
        print()

    # 重点看 223211 的簇过滤效果
    print("\n" + "=" * 100)
    print("223211 各方案 R 波位置对照（看误检簇是否消除）")
    print("=" * 100)
    ecg = load_ecg("samples/ECG_diagnostic_20260716_223211.txt")
    expected_peaks = [7,160,783,1217,1449,2459,2867,3323,3701,3818,4298,4562,4789,5179,5565,6061,
                      6538,6648,7117,7217,8435,8635,9406,10204,10297,10517,11369,11693,12135,12581,
                      12854,12998,13138,13283,13660,13933,14055,14425,14747,14912]
    print(f"原始记录: {expected_peaks}")
    for scheme_name, scheme_func in schemes:
        r_peaks, stats = scheme_func(ecg)
        print(f"{scheme_name:<18} ({stats['n']}个, CV={stats['cv']:.3f}): {r_peaks}")


if __name__ == "__main__":
    main()
