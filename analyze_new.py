"""
仔细分析两个新文件：静息.txt 和 运动后.txt
逐行读取所有段，不跳行跳字。
重点对照 HeartVoice API vs 本地算法，验证 R 波极性/T-R 比/ST 偏移新字段。
"""
import re

files = {
    "静息(新)": "samples/静息.txt",
    "运动后(新)": "samples/运动后.txt",
}

for label, path in files.items():
    print("=" * 70)
    print(f"文件: {label} ({path})")
    print("=" * 70)
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # 元信息头
    print("\n--- 元信息头 ---")
    header_end = content.find("[原始 ECG 数据]")
    print(content[:header_end].strip())

    # HeartVoice API 返回段（完整输出）
    print("\n--- [HeartVoice API 返回] 段 ---")
    hv_match = re.search(r'\[HeartVoice API 返回\](.*?)(?=\[算法提取后的结构化特征\]|\[AI 解读\]|\Z)', content, re.DOTALL)
    if hv_match:
        hv_section = hv_match.group(1).strip()
        print(hv_section)
    else:
        print("(未找到)")

    # 算法提取后的结构化特征段
    print("\n--- [算法提取后的结构化特征] 段 ---")
    feat_match = re.search(r'\[算法提取后的结构化特征\](.*?)(?=\[HeartVoice API 返回\]|\[AI 解读\]|\Z)', content, re.DOTALL)
    if feat_match:
        feat_section = feat_match.group(1).strip()
        lines = feat_section.split("\n")
        # 全局指标 + 节律判别特征
        for line in lines:
            if "[逐秒分段]" in line:
                break
            print(f"  {line}")
        # 逐秒分段表
        print("  ... [逐秒分段] 表:")
        in_seg = False
        seg_count = 0
        for line in lines:
            if "[逐秒分段]" in line:
                in_seg = True
                continue
            if in_seg:
                if line.strip():
                    print(f"    {line}")
                    seg_count += 1
                    if seg_count > 35: break

    # 原始数据统计
    print("\n--- 原始 ECG 数据统计 ---")
    raw_match = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL)
    if raw_match:
        raw_text = raw_match.group(1)
        values = [int(x) for x in raw_text.split() if x.lstrip('-').isdigit()]
        if values:
            print(f"  样本数: {len(values)}")
            print(f"  原始值范围: {min(values)} ~ {max(values)}")
            mean = sum(values) / len(values)
            deviations = [v - mean for v in values]
            print(f"  去均值后范围: {min(deviations)/1000:.3f}mV ~ {max(deviations)/1000:.3f}mV")
            rms = (sum(d*d for d in deviations) / len(deviations)) ** 0.5
            print(f"  整体 RMS: {rms/1000:.3f}mV")
            sr = 500
            seg_rms = []
            for s in range(0, len(values)//sr):
                seg = values[s*sr:(s+1)*sr]
                seg_mean = sum(seg)/len(seg)
                seg_dev = [v - seg_mean for v in seg]
                seg_rms.append((sum(d*d for d in seg_dev)/len(seg_dev))**0.5 / 1000)
            print(f"  逐秒 RMS (mV): {', '.join(f'{r:.3f}' for r in seg_rms)}")
            noise_segs = [i for i, r in enumerate(seg_rms) if r < 0.10]
            print(f"  噪声段 (RMS<0.10): {len(noise_segs)}/{len(seg_rms)} 段，时段: {noise_segs}")

    print("\n")

# 对照总结
print("=" * 70)
print("两文件对照总结")
print("=" * 70)

summary = {}
for label, path in files.items():
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    s = {}
    hv_match = re.search(r'\[HeartVoice API 返回\](.*?)(?=\[算法提取后的结构化特征\]|\[AI 解读\]|\Z)', content, re.DOTALL)
    hv = hv_match.group(1) if hv_match else ""
    s["hv_avgHr"] = re.search(r'平均心率[：:]\s*(\d+)', hv)
    s["hv_avgHr"] = s["hv_avgHr"].group(1) if s["hv_avgHr"] else "N/A"
    s["hv_minHr"] = re.search(r'最低心率[：:]\s*(\d+)', hv)
    s["hv_minHr"] = s["hv_minHr"].group(1) if s["hv_minHr"] else "N/A"
    s["hv_maxHr"] = re.search(r'最高心率[：:]\s*(\d+)', hv)
    s["hv_maxHr"] = s["hv_maxHr"].group(1) if s["hv_maxHr"] else "N/A"
    s["hv_diagnosis"] = re.search(r'诊断[：:]\s*(.+)', hv)
    s["hv_diagnosis"] = s["hv_diagnosis"].group(1).strip() if s["hv_diagnosis"] else "N/A"
    s["hv_qrs"] = re.search(r'QRS[宽度]*[：:]\s*(\d+)', hv)
    s["hv_qrs"] = s["hv_qrs"].group(1) if s["hv_qrs"] else "N/A"
    s["hv_pr"] = re.search(r'PR[间期]*[：:]\s*(\d+)', hv)
    s["hv_pr"] = s["hv_pr"].group(1) if s["hv_pr"] else "N/A"
    s["hv_qtc"] = re.search(r'QTc[：:]\s*(\d+)', hv)
    s["hv_qtc"] = s["hv_qtc"].group(1) if s["hv_qtc"] else "N/A"
    s["hv_sq"] = re.search(r'信号质量[：:]\s*([\d.]+)', hv)
    s["hv_sq"] = s["hv_sq"].group(1) if s["hv_sq"] else "N/A"
    s["hv_reverse"] = re.search(r'导联反接[：:]\s*(\S+)', hv)
    s["hv_reverse"] = s["hv_reverse"].group(1) if s["hv_reverse"] else "N/A"

    feat_match = re.search(r'\[算法提取后的结构化特征\](.*?)(?=\[HeartVoice API 返回\]|\[AI 解读\]|\Z)', content, re.DOTALL)
    feat = feat_match.group(1) if feat_match else ""
    s["lc_rPeaks"] = re.search(r'R波检测[：:]\s*(\d+)', feat)
    s["lc_rPeaks"] = s["lc_rPeaks"].group(1) if s["lc_rPeaks"] else "N/A"
    s["lc_avgHr"] = re.search(r'平均心率[：:]\s*(\d+)bpm', feat)
    s["lc_avgHr"] = s["lc_avgHr"].group(1) if s["lc_avgHr"] else "N/A"
    s["lc_hrRange"] = re.search(r'范围[：:]\s*(\d+-\d+)', feat)
    s["lc_hrRange"] = s["lc_hrRange"].group(1) if s["lc_hrRange"] else "N/A"
    s["lc_qrs"] = re.search(r'QRS宽度.*?[：:]\s*(\d+)', feat)
    s["lc_qrs"] = s["lc_qrs"].group(1) if s["lc_qrs"] else "N/A"
    s["lc_rAmp"] = re.search(r'R波平均振幅[：:]\s*([\d.]+)mV', feat)
    s["lc_rAmp"] = s["lc_rAmp"].group(1) if s["lc_rAmp"] else "N/A"
    s["lc_polarity"] = re.search(r'R波极性[：:]\s*(\S+)', feat)
    s["lc_polarity"] = s["lc_polarity"].group(1) if s["lc_polarity"] else "N/A(旧版本无此字段)"
    s["lc_tr"] = re.search(r'T/R振幅比[：:]\s*([\d.]+)', feat)
    s["lc_tr"] = s["lc_tr"].group(1) if s["lc_tr"] else "N/A(旧版本无此字段)"
    s["lc_st"] = re.search(r'ST段偏移.*?[：:]\s*([-\d.]+)', feat)
    s["lc_st"] = s["lc_st"].group(1) if s["lc_st"] else "N/A(旧版本无此字段)"
    s["lc_quality"] = re.search(r'信号质量[：:]\s*([\d.]+)', feat)
    s["lc_quality"] = s["lc_quality"].group(1) if s["lc_quality"] else "N/A"
    s["lc_poincare"] = re.search(r'Poincaré散点形态[：:]\s*(.+)', feat)
    s["lc_poincare"] = s["lc_poincare"].group(1).strip() if s["lc_poincare"] else "N/A"
    s["lc_rrVar"] = re.search(r'RR变异系数[：:]\s*([\d.]+)', feat)
    s["lc_rrVar"] = s["lc_rrVar"].group(1) if s["lc_rrVar"] else "N/A"
    summary[label] = s

print(f"\n{'指标':<24} {'静息(新)':<28} {'运动后(新)':<28}")
print("-" * 80)
rows = [
    ("HV 平均心率", "hv_avgHr"),
    ("HV 最低心率", "hv_minHr"),
    ("HV 最高心率", "hv_maxHr"),
    ("HV 诊断", "hv_diagnosis"),
    ("HV QRS(ms)", "hv_qrs"),
    ("HV PR(ms)", "hv_pr"),
    ("HV QTc(ms)", "hv_qtc"),
    ("HV 信号质量", "hv_sq"),
    ("HV 导联反接", "hv_reverse"),
    ("本地 R 波数", "lc_rPeaks"),
    ("本地 平均心率", "lc_avgHr"),
    ("本地 心率范围", "lc_hrRange"),
    ("本地 QRS(ms)", "lc_qrs"),
    ("本地 R 波振幅(mV)", "lc_rAmp"),
    ("本地 R 波极性", "lc_polarity"),
    ("本地 T/R 比", "lc_tr"),
    ("本地 ST 偏移(mV)", "lc_st"),
    ("本地 信号质量", "lc_quality"),
    ("本地 Poincaré", "lc_poincare"),
    ("本地 RR 变异系数", "lc_rrVar"),
]
for name, key in rows:
    vals = [summary[l].get(key, "N/A") for l in ["静息(新)", "运动后(新)"]]
    print(f"{name:<23} {vals[0]:<27} {vals[1]:<27}")
