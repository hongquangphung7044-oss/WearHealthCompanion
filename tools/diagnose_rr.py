#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 R 波检测：列出所有 RR 间期，标记短 RR 位置和前后 R 峰振幅"""
import os
import glob
from verify_fix_v3 import parse_ecg, detect_r_peaks_fixed

SR = 500
SAMPLES_DIR = os.path.join(os.path.join(os.path.dirname(__file__), ".."), "samples")


def main():
    files = sorted(glob.glob(os.path.join(SAMPLES_DIR, "ECG_diagnostic_*.txt")))
    for f in files:
        ecg, hv = parse_ecg(f)
        if not hv or not ecg:
            continue
        hv_hr = int(hv.get("平均心率", "0") or "0")
        if hv_hr == 0:
            continue
        peaks = detect_r_peaks_fixed(ecg)
        expected = int(hv_hr * 30 / 60)
        print("=" * 100)
        print(f"{os.path.basename(f)}  HV_HR={hv_hr}  期望R波数≈{expected}  实际={len(peaks)}")
        print("=" * 100)
        # 去基线
        n = len(ecg)
        bw = SR
        baseline = [0.0] * n
        for i in range(n):
            s, e = max(0, i - bw // 2), min(n, i + bw // 2)
            baseline[i] = sum(ecg[s:e]) / (e - s)
        hp = [ecg[i] - baseline[i] for i in range(n)]
        # 输出所有 RR + 短 RR 上下文
        short_rr_indices = []
        for i in range(1, len(peaks)):
            rr = (peaks[i] - peaks[i-1]) * 1000 // SR
            if 300 <= rr <= 500:
                short_rr_indices.append(i)
        print(f"  短 RR（300-500ms）数量: {len(short_rr_indices)}")
        for i in short_rr_indices:
            prev_r = peaks[i-1]
            curr_r = peaks[i]
            prev_amp = abs(hp[prev_r])
            curr_amp = abs(hp[curr_r])
            rr = (curr_r - prev_r) * 1000 // SR
            # 看前 5 个 RR 的均值
            recent = []
            for j in range(max(1, i-3), i):
                recent.append((peaks[j] - peaks[j-1]) * 1000 // SR)
            recent_mean = sum(recent) // len(recent) if recent else 0
            # 后 1 个 RR
            next_rr = (peaks[i+1] - peaks[i]) * 1000 // SR if i+1 < len(peaks) else 0
            print(f"  短RR@#{i}: R[{prev_r}] amp={prev_amp:.0f} → R[{curr_r}] amp={curr_amp:.0f}, "
                  f"RR={rr}ms (前3均={recent_mean}, 后RR={next_rr})")
        # 输出 RR 序列直方图（10ms 桶）
        rr_list = [(peaks[i] - peaks[i-1]) * 1000 // SR for i in range(1, len(peaks))]
        if rr_list:
            print(f"  RR 范围: {min(rr_list)}-{max(rr_list)}ms, 中位数={sorted(rr_list)[len(rr_list)//2]}ms")
            buckets = {}
            for r in rr_list:
                b = (r // 50) * 50
                buckets[b] = buckets.get(b, 0) + 1
            print("  RR 直方图（50ms 桶）:")
            for b in sorted(buckets):
                print(f"    {b}-{b+49}ms: {buckets[b]}")
        print()


if __name__ == "__main__":
    main()
