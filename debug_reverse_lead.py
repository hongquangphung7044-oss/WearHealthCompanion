"""
调试导联反接样本（samples/ECG_diagnostic_20260716_223211.txt）：
HeartVoice 诊断 SN（窦性）+ 导联反接，78bpm，0 早搏。
但本地 v5 算法检出 40 个 R 波（数量对）却产生 14/29=48% 异常 RR，
RR 变异系数 0.545，Poincaré 扇形，被 DS 误判为房颤。

目标：
1. 复现 v5 算法（k=1.7, ±50ms 精修），对照文件中的 40 个 R 波位置
2. 分析误检模式：哪些 R 波是真，哪些是 S 波/T 波被误检
3. 定位根因：导联反接（R 波负向）+ 精修用 abs(hp) 是否把 S 波当 R 峰

v5 与旧 detect_mean_std 的差异：
- k: 2.0 → 1.7
- 精修窗口: sr//40 (±25ms) → sr//20 (±50ms)
"""
import math
import re


def load_ecg(path):
    """从诊断包 .txt 提取原始 ECG 数据（mV×1000 整数列表）"""
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def compute_envelope(ecg, sr=500):
    """复现 EcgFeatureExtractor 预处理：去基线(1s移动平均) + |梯度|/2 + 150ms boxcar"""
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
    return env, hp, baseline


def detect_v5(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
    """复现当前 Kotlin v5 算法：4秒分段 mean+k*std 阈值 + ±50ms 精修 + 1.66×回溯补检"""
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

    refractory = sr // 5  # 200ms
    check_range = sr // 50  # ±10ms 局部最大
    refine_range = sr // 20  # ±50ms 精修（v5 关键改动）
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
        # 精修：±50ms 找 |hp| 最大（v5）
        r_lo, r_hi = max(0, i - refine_range), min(len(hp), i + refine_range + 1)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检（v5：带 ±50ms 精修）
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


def analyze_peak_polarity(ecg, hp, baseline, r_peaks, sr=500):
    """分析每个检测到的 R 峰位置的波形特征：原始值、去基线值、极性、是否成对"""
    print(f"\n{'idx':>6} {'raw':>8} {'hp':>8} {'极性':>4} {'|hp|':>8} {'RR_ms':>7} {'标记'}")
    prev = None
    for i, idx in enumerate(r_peaks):
        raw = ecg[idx]
        h = hp[idx]
        polarity = "正" if h > 0 else "负"
        rr_ms = int((idx - prev) * 1000 / sr) if prev is not None else 0
        # 标记短 RR（<400ms 疑似误检双峰）
        mark = ""
        if 0 < rr_ms < 400:
            mark = " ← 短RR(疑似双峰误检)"
        elif rr_ms >= 1500:
            mark = " ← 长RR(疑似漏检)"
        print(f"{idx:>6} {raw:>8} {h:>8.1f} {polarity:>4} {abs(h):>8.1f} {rr_ms:>7}{mark}")
        prev = idx


def find_cluster_anomalies(r_peaks, sr=500, short_threshold_ms=400):
    """找出成簇误检：连续 R 波间隔 < short_threshold_ms 的组"""
    clusters = []
    current_cluster = [r_peaks[0]]
    for i in range(1, len(r_peaks)):
        rr_ms = (r_peaks[i] - r_peaks[i - 1]) * 1000 / sr
        if rr_ms < short_threshold_ms:
            current_cluster.append(r_peaks[i])
        else:
            if len(current_cluster) >= 2:
                clusters.append(current_cluster)
            current_cluster = [r_peaks[i]]
    if len(current_cluster) >= 2:
        clusters.append(current_cluster)
    return clusters


def main():
    path = "samples/ECG_diagnostic_20260716_223211.txt"
    ecg = load_ecg(path)
    print(f"加载 {path}: {len(ecg)} 点, 时长 {len(ecg)/500:.1f}s")

    # 文件中记录的本地算法 R 波位置（用于对照复现是否正确）
    expected_peaks = [7,160,783,1217,1449,2459,2867,3323,3701,3818,4298,4562,4789,5179,5565,6061,
                      6538,6648,7117,7217,8435,8635,9406,10204,10297,10517,11369,11693,12135,12581,
                      12854,12998,13138,13283,13660,13933,14055,14425,14747,14912]
    print(f"文件记录 R 波数: {len(expected_peaks)}")

    env, hp, baseline = compute_envelope(ecg)
    r_peaks = detect_v5(ecg, env, hp)
    print(f"Python v5 复现 R 波数: {len(r_peaks)}")

    # 对照位置（允许 ±2 样本偏差，Python/Kotlin 浮点差异）
    match_count = 0
    mismatch = []
    for ep in expected_peaks:
        found = any(abs(ep - rp) <= 2 for rp in r_peaks)
        if found:
            match_count += 1
        else:
            mismatch.append(ep)
    print(f"位置匹配: {match_count}/{len(expected_peaks)}")
    if mismatch:
        print(f"未匹配位置: {mismatch[:20]}")

    # RR 异常统计
    rr = [(r_peaks[i] - r_peaks[i-1]) * 1000 / 500 for i in range(1, len(r_peaks))]
    rr_valid = [x for x in rr if 300 <= x <= 2500]
    if rr_valid:
        mean_rr = sum(rr_valid) / len(rr_valid)
        abnormal = [x for x in rr_valid if abs(x - mean_rr) / mean_rr > 0.20]
        print(f"\nRR 统计: 总 {len(rr)}, 有效(300-2500ms) {len(rr_valid)}, 均值 {mean_rr:.0f}ms")
        print(f"异常 RR(偏离均值>20%): {len(abnormal)}/{len(rr_valid)} = {len(abnormal)/len(rr_valid)*100:.0f}%")
        if mean_rr > 0:
            cv = math.sqrt(sum((x-mean_rr)**2 for x in rr_valid)/len(rr_valid)) / mean_rr
            print(f"变异系数 CV: {cv:.3f} (>0.20 触发'扇形/房颤'判读)")

    # 成簇误检分析
    print("\n" + "=" * 80)
    print("成簇误检分析（连续 R 波间隔 <400ms = 疑似 S 波/T 波被误检为 R 波）")
    print("=" * 80)
    clusters = find_cluster_anomalies(r_peaks)
    for ci, cluster in enumerate(clusters):
        spans_ms = (cluster[-1] - cluster[0]) * 1000 / 500
        gaps = [(cluster[i+1]-cluster[i])*1000/500 for i in range(len(cluster)-1)]
        print(f"簇 {ci+1}: {cluster}  跨度 {spans_ms:.0f}ms  间隔 {gaps}")

    # 逐峰极性分析（看双峰是否一个是负 R 峰 + 一个是正 S 峰）
    print("\n" + "=" * 80)
    print("逐峰极性分析（导联反接时 R 波负向，S 波正向；若双峰极性相反说明精修跳到 S 波）")
    print("=" * 80)
    analyze_peak_polarity(ecg, hp, baseline, r_peaks)

    # HeartVoice 金标准对照
    print("\n" + "=" * 80)
    print("HeartVoice 金标准: 78bpm SN 窦性 0早搏 导联反接=true")
    print("=" * 80)
    # 78bpm 期望 RR ≈ 769ms，30.9s 期望 ~40 个 R 波
    expected_rr_ms = 60000 / 78
    expected_r_count = int(30.9 * 78 / 60)
    print(f"期望 RR: {expected_rr_ms:.0f}ms, 期望 R 波数: {expected_r_count}")
    print(f"实际 R 波数: {len(r_peaks)} (数量{'对' if abs(len(r_peaks)-expected_r_count)<=3 else '不对'})")
    print(f"实际有效 RR 均值: {sum(rr_valid)/len(rr_valid):.0f}ms (期望 {expected_rr_ms:.0f}ms)")


if __name__ == "__main__":
    main()
