"""
Debug: 真实样本的分段阈值对比（mean+2std vs 百分位0.92 vs min）
找出为什么真实静息信号过度检出
"""
import re
import math
from verify_red import compute_envelope, percentile


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


files = {
    "运动后": ("samples/运动后.txt", 107),
    "静息(新)": ("samples/静息.txt", 54),
    "换右手": ("samples/心电api静息换右手.txt", 59),
    "活动后(旧)": ("samples/心电api活动后.txt", 72),
    "静息(旧)": ("samples/心电api静息.txt", 59),
}

for label, (path, hv) in files.items():
    ecg = load_ecg(path)
    env, hp = compute_envelope(ecg)
    sr = 500
    seg_len = sr * 4
    print(f"\n{'='*80}")
    print(f"{label} (HV={hv}bpm)")
    print(f"{'段':<12} {'mean+2std':<12} {'pct0.92':<12} {'min':<12} {'segMax':<12} {'min取谁':<10}")
    print(f"{'-'*80}")
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        mean = sum(seg) / len(seg)
        var = sum((x - mean) ** 2 for x in seg) / len(seg)
        std = math.sqrt(var)
        thr_ms = mean + std * 2.0
        seg_sorted = sorted(seg)
        thr_pct = percentile(seg_sorted, 0.92)
        thr_min = max(min(thr_pct, thr_ms), 3.0)
        seg_max = max(seg)
        chosen = "mean+2std" if thr_ms <= thr_pct else "pct0.92"
        overdetect = "⚠过低" if thr_min < seg_max * 0.5 else ""
        print(f"{ss//sr}s-{se//sr}s    {thr_ms:<12.2f} {thr_pct:<12.2f} {thr_min:<12.2f} {seg_max:<12.2f} {chosen:<10} {overdetect}")
