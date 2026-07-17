#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 R 波极性：检查样本数据的真实 R 波方向
看 R 峰值相对基线是正还是负，以及波形整体形态
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


def detect_r_peaks_basic(ecg, sr=SR):
    n = len(ecg)
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
        peaks.append(best_idx)
        last_peak = best_idx
    return peaks, hp, baseline


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    print("=" * 100)
    print("R 波极性诊断")
    print("=" * 100)
    for f in files:
        ecg, hv = parse_ecg(f)
        if not ecg:
            continue
        peaks, hp, baseline = detect_r_peaks_basic(ecg)
        if not peaks:
            print(f"{os.path.basename(f)}: 无 R 波")
            continue
        # 全局信号统计
        ecg_min = min(ecg)
        ecg_max = max(ecg)
        ecg_median = median(ecg)
        # 检查 R 峰值方向（相对全局基线 median）
        r_vals_raw = [ecg[p] for p in peaks[:10]]
        r_vals_hp = [hp[p] for p in peaks[:10]]
        pos_count = sum(1 for v in r_vals_hp if v > 0)
        neg_count = sum(1 for v in r_vals_hp if v < 0)
        # 检查前 5 个 R 波周围的波形（±100ms）
        print(f"\n{os.path.basename(f)}:")
        print(f"  全局 raw: min={ecg_min}, max={ecg_max}, median={ecg_median}")
        print(f"  R 波数: {len(peaks)}")
        print(f"  前 10 个 R 峰 raw 值: {r_vals_raw}")
        print(f"  前 10 个 R 峰 hp(去基线) 值: {r_vals_hp}")
        print(f"  极性投票: 正向={pos_count}, 负向={neg_count}")
        # 打印第一个 R 波 ±150ms 的波形采样（每 10ms 一个点）
        if peaks:
            r0 = peaks[0]
            print(f"  第一个 R 波(idx={r0}) 周围波形(raw mV×1000, 每 10ms):")
            for offset_ms in range(-150, 160, 10):
                idx = r0 + SR * offset_ms // 1000
                if 0 <= idx < len(ecg):
                    bar = "+" * max(0, ecg[idx] // 50) + "-" * max(0, -ecg[idx] // 50)
                    print(f"    {offset_ms:+4d}ms: {ecg[idx]:+6d}  {bar}")


if __name__ == "__main__":
    main()
