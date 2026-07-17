"""
分析最新上传的三个测试样本（dsmax/ds均衡/专业），用 v6 算法复现检测结果。

v6 = v5 + 精修二次不应期验证 + 回溯补检T波排除

重点分析：
1. 三个样本的 R 峰数、CV、reliability 等级
2. min/max 心率为 0 的触发条件（rrIntervals.size < 3 时全 0）
3. PPG 辅助心率的作用（ppgReferenceHr）
4. 对比 HV 诊断结果（专业.txt 是 heartvoice 路径）
"""
import math
import os
import sys
import re

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_rhythm_fix import load_ecg, compute_envelope, filter_ectopic, rhythm_features_fixed
from test_reliability import compute_reliability, extract_segments, filter_noise_r_peaks, compute_rr, compute_hr
from test_refine_fix import detect_v5_fixed as detect_v6


def parse_txt_file(path):
    """解析诊断包 .txt，提取 HV/DS 返回段 + 本地特征段"""
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    info = {"path": path, "method": "", "hv_section": "", "local_section": "", "ecg": []}
    # 分析方法
    m = re.search(r'分析方法:(\S+)', content)
    if m:
        info["method"] = m.group(1)

    # HV/DS 返回段
    hv_match = re.search(r'\[HeartVoice API 返回\](.*?)(?=\n\[|\n={5,}|\Z)', content, re.DOTALL)
    if hv_match:
        info["hv_section"] = hv_match.group(1).strip()[:2000]
    else:
        ds_match = re.search(r'\[DeepSeek.*?返回\](.*?)(?=\n\[|\n={5,}|\Z)', content, re.DOTALL)
        if ds_match:
            info["hv_section"] = ds_match.group(1).strip()[:2000]

    # 本地特征段
    local_match = re.search(r'\[本地特征\](.*?)(?=\n\[|\n={5,}|\Z)', content, re.DOTALL)
    if local_match:
        info["local_section"] = local_match.group(1).strip()[:3000]

    # ECG 数据
    ecg_match = re.search(r'\[原始 ECG 数据\](.*?)(?=\n\[|\Z)', content, re.DOTALL)
    if ecg_match:
        lines = ecg_match.group(1).strip().split('\n')
        info["ecg"] = [int(x) for x in lines if x.strip() and x.strip().lstrip('-').isdigit()]
    return info


def extract_field(text, pattern):
    """从文本提取字段"""
    m = re.search(pattern, text)
    return m.group(1) if m else ""


def compute_heart_rate_stats_v6(rr_intervals, age_years=0):
    """复现 computeHeartRateStats（含 min/max=0 的触发条件）"""
    if len(rr_intervals) < 3:
        return {"avg": 0, "min": 0, "max": 0, "reason": f"RR数({len(rr_intervals)})<3,全返回0"}
    sorted_rr = sorted(rr_intervals)
    median_rr = sorted_rr[len(sorted_rr) // 2]
    if median_rr <= 0:
        return {"avg": 0, "min": 0, "max": 0, "reason": "medianRR<=0"}
    median_hr = int(60000.0 / median_rr)
    if not (40 <= median_hr <= 200):
        return {"avg": 0, "min": 0, "max": 0, "reason": f"medianHR={median_hr}不在40-200"}
    variation = 0.20 if age_years <= 0 else max(0.05, min(0.20, (23.2 - 0.35 * age_years) / 100.0))
    lower = median_rr * (1 - variation)
    upper = median_rr * (1 + variation)
    normal = [r for r in rr_intervals if lower <= r <= upper]
    rr_range = normal if len(normal) >= 3 else rr_intervals
    min_rr = min(rr_range)
    max_rr = max(rr_range)
    return {
        "avg": median_hr,
        "min": int(60000.0 / max_rr),
        "max": int(60000.0 / min_rr),
        "reason": f"medianRR={median_rr},variation±{variation*100:.0f}%,normal={len(normal)}/{len(rr_intervals)}",
    }


def analyze(path, label):
    print(f"\n{'=' * 100}")
    print(f"样本: {label} ({os.path.basename(path)})")
    print(f"{'=' * 100}")
    if not os.path.exists(path):
        print("  [文件不存在]")
        return
    info = parse_txt_file(path)
    ecg = info["ecg"]
    if not ecg:
        print("  [无 ECG 数据]")
        return
    sr = 500
    duration = len(ecg) / sr
    print(f"  分析方法: {info['method']}, 时长: {duration:.1f}s, 数据点: {len(ecg)}")

    # 基线统计
    baseline = sum(ecg[:sr]) / sr  # 第一秒均值
    print(f"  基线(首秒均值): {baseline:.0f} ({baseline/1000:.2f}mV)")

    # v6 检测
    env, hp = compute_envelope(ecg, sr)
    r_peaks = detect_v6(ecg, env, hp, sr)
    seg_raw = extract_segments(ecg, r_peaks, sr)
    effective = filter_noise_r_peaks(r_peaks, seg_raw, sr)
    rr = compute_rr(effective, sr)
    rr_for_hrv = filter_ectopic(rr)
    hr = compute_heart_rate_stats_v6(rr_for_hrv)
    avg_hr_simple = compute_hr(rr_for_hrv)
    seg = extract_segments(ecg, effective, sr)
    rel = compute_reliability(r_peaks, effective, rr, hr["avg"], duration, seg, sr)
    rhy = rhythm_features_fixed(rr)

    print(f"\n  [v6 算法结果]")
    print(f"  R 峰数: {len(r_peaks)} (有效 {len(effective)})")
    print(f"  期望 R 峰数: {duration * hr['avg'] / 60:.1f} (基于 avgHr={hr['avg']})" if hr['avg'] > 0 else f"  期望 R 峰数: 无法估算(avgHr=0)")
    print(f"  心率: avg={hr['avg']} min={hr['min']} max={hr['max']}")
    print(f"    min/max 触发: {hr['reason']}")
    print(f"  极端RR: {rel['extreme']*100:.0f}% 有效RR: {rel['valid']*100:.0f}% 净跳变: {rel['jump']*100:.0f}%")
    print(f"  一致性: {rel['consistency']:.2f} 异常段: {rel['abnormal_seg']*100:.0f}%")
    print(f"  CV: {rhy['cv']:.3f} 形态: {rhy['pattern']}")
    print(f"  可靠性: {rel['level']} {rel['reason']}")

    # 对比 .txt 里的本地特征（旧版本快照）
    if info["local_section"]:
        print(f"\n  [.txt 快照本地特征]（旧版本）")
        local = info["local_section"]
        for line in local.split('\n')[:15]:
            if line.strip():
                print(f"    {line.strip()}")

    # HV/DS 返回段
    if info["hv_section"]:
        print(f"\n  [{info['method']} 返回段]")
        hv = info["hv_section"]
        # 提取关键诊断
        for line in hv.split('\n')[:20]:
            if line.strip() and any(k in line for k in ['诊断', '结论', '心率', '节律', '窦性', '房颤', '早搏', 'R波', 'RR', 'CV', '可靠性', 'min', 'max']):
                print(f"    {line.strip()}")


def main():
    samples = [
        ("专业(heartvoice)", "samples/专业.txt"),
        ("ds均衡(ds_flash_balanced)", "samples/ds均衡.txt"),
        ("dsmax(ds_pro_max)", "samples/dsmax.txt"),
    ]
    print("三个最新测试样本 v6 算法分析")
    print("重点关注：min/max=0 触发条件、PPG 辅助、reliability 等级")
    for label, path in samples:
        analyze(path, label)


if __name__ == "__main__":
    main()
