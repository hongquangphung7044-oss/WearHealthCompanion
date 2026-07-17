#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断单个 R 波周围的波形，看 QRS 真实形态"""
import os
import glob
from statistics import median

SAMPLES_DIR = os.path.join(os.path.join(os.path.dirname(__file__), ".."), "samples")
SR = 500


def parse_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")
    ecg = []
    section = None
    hv = {}
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


def main():
    # 取 140542 的第 5 个 R 波（索引 ~4000 附近，振幅大）
    f = os.path.join(SAMPLES_DIR, "ECG_diagnostic_20260717_140542.txt")
    ecg, hv = parse_ecg(f)
    print(f"HV QRS={hv.get('QRS宽度')}")
    # R 峰在 4396（之前诊断输出）
    r_idx = 4396
    # 打印 R 峰前后 ±100ms 的波形（每 2ms 一个点，500Hz → 10 样本 = 20ms）
    print(f"\nR 峰位置: {r_idx} ({r_idx*1000//SR}ms)")
    print(f"{'样本':<8} {'时间ms':<8} {'原始值':<10} {'去基线':<10} {'导数':<10}")
    print("-" * 50)
    q_start = max(0, r_idx - SR * 100 // 1000)
    s_end = min(len(ecg), r_idx + SR * 100 // 1000)
    seg = ecg[q_start:s_end]
    seg_med = median(seg)
    print(f"段中位数(基线): {seg_med:.0f}")
    print()
    for i in range(q_start, s_end, 2):  # 每 4ms 打印一次
        rel = (i - r_idx) * 1000 // SR
        hp = ecg[i] - seg_med
        prev = ecg[max(0, i - 1)] - seg_med
        nxt = ecg[min(len(ecg) - 1, i + 1)] - seg_med
        deriv = (nxt - prev) / 2.0
        marker = ""
        if rel == 0:
            marker = " ← R峰"
        print(f"{i:<8} {rel:<8} {ecg[i]:<10} {hp:<10.0f} {deriv:<10.1f} {marker}")

    print("\n\n分析：QRS 起止点应在导数从 0 上升到峰值再回到 0 的区间")
    print("如果 QRS 真实 98ms，则 Q 点应在 R 峰前 ~50ms，S 点应在 R 峰后 ~48ms")


if __name__ == "__main__":
    main()
