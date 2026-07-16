"""
测试不同 k 值的 mean+k*std 阈值，找运动后改善 + 静息不回归的平衡点。
同时测试 min(mean+k*std, pct0.92) 组合。
"""
import re
import math
import random
from verify_red import compute_envelope, percentile, detect_mean_std


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def detect_kstd(ecg, env, hp, sr=500, k=2.0, pct_cap=None, floor=3.0, seg_sec=4):
    """mean+k*std 阈值，可选百分位上限 cap"""
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
        thr = mean + k * std
        if pct_cap is not None:
            seg_sorted = sorted(seg)
            thr_pct = percentile(seg_sorted, pct_cap)
            thr = min(thr, thr_pct)
        thr = max(thr, floor)
        for i in range(ss, se):
            thresholds[i] = thr

    refractory = sr // 5
    check_range = sr // 50
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
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx

    # 回溯补检
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
                ss2, se2 = r_peaks[k_idx - 1] + refractory, r_peaks[k_idx] - refractory
                if se2 <= ss2:
                    continue
                best_b, best_bv = -1, back_thr
                for j in range(ss2, se2 + 1):
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
                    r_lo, r_hi = max(0, best_b - sr // 40), min(len(hp), best_b + sr // 40)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def compute_hr(r_peaks, sr=500):
    if len(r_peaks) < 2:
        return 0
    rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / sr for i in range(1, len(r_peaks))]
    rr = [x for x in rr if 300 <= x <= 2500]
    if len(rr) < 3:
        return 0
    mean = sum(rr) / len(rr)
    filtered = [x for x in rr if abs(x - mean) / mean <= 0.20] if mean > 0 else rr
    if not filtered:
        filtered = rr
    if len(filtered) < 3:
        return 0
    rr_sorted = sorted(filtered)
    med = rr_sorted[len(rr_sorted) // 2]
    if med <= 0:
        return 0
    hr = int(60000 / med)
    return hr if 40 <= hr <= 200 else 0


def short_rr(r_peaks):
    rr = [(r_peaks[i] - r_peaks[i - 1]) * 1000 / 500 for i in range(1, len(r_peaks))]
    return sum(1 for x in rr if x < 400)


def synthetic_ecg(duration_sec, r_peak_times, r_amp_mv=1.0, noise_level=0.01, seed=42,
                  r_sigma=0.01):
    sr = 500
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma_sq2 = 2 * r_sigma * r_sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        v += (rng.random() - 0.5) * 2 * noise_level
        data.append(int(v * 1000))
    return data


def synthetic_ecg_motion(duration_sec, r_peak_times, r_amp_mv=0.5, base_noise=0.15,
                         muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=200):
    sr = 500
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma = 0.01
    sigma_sq2 = 2 * sigma * sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
        v += (rng.random() - 0.5) * 2 * base_noise
        v += (rng.random() - 0.5) * 2 * muscle_noise
        if rng.random() < burst_prob:
            v += rng.gauss(0, 1) * burst_amp
        data.append(int(v * 1000))
    return data


# 合成信号
r_times = [i * 0.857 for i in range(35)]
r_times5 = []
t = 0.5
while t < 30.0:
    r_times5.append(t)
    t += 0.56

configs = [
    ("mean+2.0std (旧)", 2.0, None),
    ("mean+1.8std", 1.8, None),
    ("mean+1.5std", 1.5, None),
    ("mean+1.2std", 1.2, None),
    ("min(mean+1.8std, pct0.92)", 1.8, 0.92),
    ("min(mean+1.5std, pct0.92)", 1.5, 0.92),
]

print("=" * 100)
print("合成信号：不同 k 值 + 可选百分位 cap")
print("=" * 100)
print(f"{'方案':<28} | {'干净70(33-37)':<14} | {'中噪(>=20)':<10} | {'运动s200(>=33)':<14} | {'运动s201':<10}")
print("-" * 100)

for label, k, pct_cap in configs:
    ecg1 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
    env, hp = compute_envelope(ecg1)
    r1 = detect_kstd(ecg1, env, hp, k=k, pct_cap=pct_cap)

    ecg2 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.15, seed=100)
    env, hp = compute_envelope(ecg2)
    r2 = detect_kstd(ecg2, env, hp, k=k, pct_cap=pct_cap)

    results_motion = []
    for seed in [200, 201]:
        ecg5 = synthetic_ecg_motion(30.0, r_times5, r_amp_mv=0.5, base_noise=0.15,
                                    muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=seed)
        env, hp = compute_envelope(ecg5)
        r5 = detect_kstd(ecg5, env, hp, k=k, pct_cap=pct_cap)
        results_motion.append(len(r5))

    print(f"{label:<28} | {len(r1):<14} | {len(r2):<10} | {results_motion[0]:<14} | {results_motion[1]:<10}")

# 真实样本
print(f"\n{'=' * 100}")
print("真实样本：不同 k 值")
print("=" * 100)

files = {
    "运动后(新)": ("samples/运动后.txt", 107, 53),
    "静息(新)": ("samples/静息.txt", 54, 27),
    "换右手(旧)": ("samples/心电api静息换右手.txt", 59, 30),
    "活动后(旧)": ("samples/心电api活动后.txt", 72, 36),
    "静息(旧)": ("samples/心电api静息.txt", 59, 30),
}

for label, k, pct_cap in configs:
    print(f"\n--- {label} ---")
    print(f"  {'文件':<14} {'HV':<5} {'R/HR/短':<14} {'判定':<8}")
    for flabel, (path, hv_hr, expected_r) in files.items():
        ecg = load_ecg(path)
        env, hp = compute_envelope(ecg)
        r = detect_kstd(ecg, env, hp, k=k, pct_cap=pct_cap)
        hr = compute_hr(r)
        diff = abs(hr - hv_hr) if hr > 0 and hv_hr > 0 else 999
        verdict = "✅" if diff < 15 else ("⚠" if hr > 0 else "❌")
        print(f"  {flabel:<14} {hv_hr:<5} {len(r):<2}/{hr:<3}/{short_rr(r):<2}      {verdict}")
