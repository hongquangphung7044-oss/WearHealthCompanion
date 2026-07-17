"""
精确诊断三个新样本的超短 RR（208-276ms）来源。

问题：v6 的 200ms 不应期让 208-276ms 的 RR 通过，但这些仍被算极端 RR。
需要搞清楚这些超短 RR 是：
1. 主检测精修后两 R 峰距离 208-276ms（envelope 双峰间距 250-330ms，精修后缩短）
2. 回溯补检峰距主检测 R 峰 208-276ms
3. 两个回溯补检峰之间 208-276ms
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope
from test_refine_fix import detect_v5_fixed as detect_v6


def detect_v6_with_source(ecg, env, hp, sr=500):
    """返回 [(idx, source)] source='main'/'backfill'"""
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

    refractory = sr // 5
    check_range = sr // 50
    r_peaks = []
    sources = []
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
        if r_peaks and (best_idx - r_peaks[-1]) < refractory:
            continue
        r_peaks.append(best_idx)
        sources.append("main")
        last_r_peak = best_idx

    if len(r_peaks) >= 5:
        extra = []
        extra_src = []
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
                    if all(abs(r_idx - p) >= refractory for p in r_peaks):
                        prev_r = max((p for p in r_peaks if p < r_idx), default=-99999)
                        offset_to_prev = (r_idx - prev_r) * 1000 / sr
                        if 200 <= offset_to_prev <= 450:
                            continue
                        extra.append(r_idx)
                        extra_src.append("backfill")
        r_peaks.extend(extra)
        sources.extend(extra_src)
        # 排序时保持 source 对齐
        combined = sorted(zip(r_peaks, sources))
        r_peaks = [c[0] for c in combined]
        sources = [c[1] for c in combined]
    return list(zip(r_peaks, sources))


def analyze(path, label):
    print(f"\n{'=' * 90}")
    print(f"样本: {label}")
    print(f"{'=' * 90}")
    if not os.path.exists(path):
        return
    ecg = load_ecg(path)
    sr = 500
    env, hp = compute_envelope(ecg, sr)
    peaks_with_src = detect_v6_with_source(ecg, env, hp, sr)

    # 找超短 RR（< 300ms）并分析来源
    short_rrs = []
    for i in range(1, len(peaks_with_src)):
        rr = int((peaks_with_src[i][0] - peaks_with_src[i - 1][0]) * 1000 / sr)
        if rr < 300:
            short_rrs.append((i, rr, peaks_with_src[i - 1], peaks_with_src[i]))

    print(f"超短 RR (<300ms) 数: {len(short_rrs)}")
    if short_rrs:
        print(f"  {'序号':>4} {'RRms':>6} {'前峰s':>7}(源) {'后峰s':>7}(源) {'类型'}")
        for i, rr, (p1, s1), (p2, s2) in short_rrs:
            if s1 == "main" and s2 == "main":
                t = "主-主精修过近"
            elif s1 == "main" and s2 == "backfill":
                t = "主-补检过近"
            elif s1 == "backfill" and s2 == "main":
                t = "补检-主过近"
            else:
                t = "补检-补检过近"
            print(f"  {i:>4} {rr:>6} {p1/sr:>7.2f}({s1[:3]}) {p2/sr:>7.2f}({s2[:3]}) {t}")


def main():
    samples = [
        ("专业", "samples/专业.txt"),
        ("ds均衡", "samples/ds均衡.txt"),
        ("dsmax", "samples/dsmax.txt"),
    ]
    print("超短 RR 来源精确诊断")
    for label, path in samples:
        analyze(path, label)


if __name__ == "__main__":
    main()
