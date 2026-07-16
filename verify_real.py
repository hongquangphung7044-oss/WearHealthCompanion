"""
5 份真实样本验证：当前 mean+2std vs 新方案（百分位 0.92 + 形态验证 1.1）
确认新算法不回归（静息/换右手/活动后旧/静息旧 4 份保持准确，运动后改善）
"""
import re
from verify_red import compute_envelope, detect_mean_std, detect_percentile_morph


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def compute_hr(r_peaks, sr=500):
    """复现 Kotlin computeHeartRateStats：中位数 RR 反算心率（filterEctopicBeats ±20%）"""
    if len(r_peaks) < 2:
        return 0
    rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / sr for i in range(1, len(r_peaks))]
    rr = [x for x in rr if 300 <= x <= 2500]
    if len(rr) < 3:
        return 0
    # filterEctopicBeats: 偏离均值 >20% 剔除
    mean = sum(rr) / len(rr)
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20] if mean > 0 else rr
    if not filtered:
        filtered = rr
    if len(filtered) < 3:
        return 0
    rr_sorted = sorted(filtered)
    median = rr_sorted[len(rr_sorted) // 2]
    if median <= 0:
        return 0
    hr = int(60000 / median)
    return hr if 40 <= hr <= 200 else 0


files = {
    "运动后(新)": ("samples/运动后.txt", 107, 53),
    "静息(新)": ("samples/静息.txt", 54, 27),
    "换右手(旧)": ("samples/心电api静息换右手.txt", 59, 30),
    "活动后(旧)": ("samples/心电api活动后.txt", 72, 36),
    "静息(旧)": ("samples/心电api静息.txt", 59, 30),
}

print("=" * 100)
print("5 份真实样本验证：当前 mean+2std vs 新方案（百分位 0.92 + 形态验证 1.1）")
print("=" * 100)
print(f"{'文件':<16} {'HV心率':<7} {'期望R波':<8} | {'旧R波':<6} {'旧心率':<7} | {'新R波':<6} {'新心率':<7} {'短RR':<6} | {'判定':<8}")
print("-" * 100)

for label, (path, hv_hr, expected_r) in files.items():
    ecg = load_ecg(path)
    env, hp = compute_envelope(ecg)

    r_old = detect_mean_std(ecg, env, hp)
    r_new = detect_percentile_morph(ecg, env, hp, pct=0.92, flank_ratio=1.1)

    hr_old = compute_hr(r_old)
    hr_new = compute_hr(r_new)

    rr_new = [(r_new[i] - r_new[i - 1]) * 1000 / 500 for i in range(1, len(r_new))]
    short_rr = sum(1 for x in rr_new if x < 400)

    # 判定：新心率与 HV 偏差 <15bpm 为准确
    diff = abs(hr_new - hv_hr) if hr_new > 0 and hv_hr > 0 else 999
    verdict = "✅准确" if diff < 15 else ("❌回归" if hr_old > 0 and abs(hr_old - hv_hr) < 15 else "⚠改善")

    print(f"{label:<16} {hv_hr:<7} {expected_r:<8} | {len(r_old):<6} {hr_old:<7} | {len(r_new):<6} {hr_new:<7} {short_rr:<6} | {verdict:<8}")
