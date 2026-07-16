"""
方案 B：median + k * MAD/0.6745（稳健统计，抗右偏长尾）
- MAD = median(|x - median|)，对右偏长尾稳健（不被肌电突发极高值拉高）
- 0.6745 是正态分布一致性常数：MAD/0.6745 ≈ std（正态分布下）
- 对干净信号（双峰分布）：median 在噪声模，MAD 反映噪声散布 → 阈值 ≈ mean+2std（不降）
- 对运动后（右偏长尾）：median 稳健，MAD 稳健 → 阈值不被长尾拉高（比 mean+2std 低，R 波多检出）

对比 4 方案：
1. 旧 mean+2std
2. min(pct0.92, mean+2std) — 真实静息过度检出
3. median + 2*MAD/0.6745 — 期望：静息不降，运动后降
4. median + 3*MAD/0.6745 — 更保守（k=3）
"""
import re
import math
import random
from verify_red import compute_envelope, percentile


def load_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def median(sorted_list):
    if not sorted_list:
        return 0.0
    n = len(sorted_list)
    return sorted_list[n // 2] if n % 2 == 1 else (sorted_list[n // 2 - 1] + sorted_list[n // 2]) / 2


def detect_mad(ecg, env, hp, sr=500, k=2.0, floor=3.0, seg_sec=4):
    """方案 B：median + k*MAD/0.6745 稳健阈值"""
    seg_len = sr * seg_sec
    thresholds = [0.0] * len(env)
    for ss in range(0, len(env), seg_len):
        se = min(len(env), ss + seg_len)
        seg = env[ss:se]
        if not seg:
            continue
        seg_sorted = sorted(seg)
        med = median(seg_sorted)
        abs_devs = sorted(abs(x - med) for x in seg)
        mad = median(abs_devs)
        robust_std = mad / 0.6745
        thr = max(med + k * robust_std, floor)
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
        # 精修（±25ms 原始范围）
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


# ===== 合成信号验证 =====
print("=" * 90)
print("合成信号验证：MAD 稳健阈值 vs mean+2std")
print("=" * 90)

# 场景1: 干净 70bpm（期望 33-37）
r_times = [i * 0.857 for i in range(35)]
ecg1 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg1)
r_mad2 = detect_mad(ecg1, env, hp, k=2.0)
r_mad3 = detect_mad(ecg1, env, hp, k=3.0)
print(f"[1] 干净70bpm(期望33-37): MAD k=2 → {len(r_mad2)}  MAD k=3 → {len(r_mad3)}")

# 场景2: 中等噪声（期望 >=20）
ecg2 = synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.15, seed=100)
env, hp = compute_envelope(ecg2)
r_mad2 = detect_mad(ecg2, env, hp, k=2.0)
r_mad3 = detect_mad(ecg2, env, hp, k=3.0)
print(f"[2] 中等噪声(期望>=20):   MAD k=2 → {len(r_mad2)}  MAD k=3 → {len(r_mad3)}")

# 场景5: 运动后 RED（期望 >=33）
r_times5 = []
t = 0.5
while t < 30.0:
    r_times5.append(t)
    t += 0.56
print(f"\n[5] 运动后 RED(期望>=33) 多 seed:")
for seed in [200, 201, 202, 300, 400, 500]:
    ecg5 = synthetic_ecg_motion(30.0, r_times5, r_amp_mv=0.5, base_noise=0.15,
                                muscle_noise=0.4, burst_prob=0.03, burst_amp=1.0, seed=seed)
    env, hp = compute_envelope(ecg5)
    r_mad2 = detect_mad(ecg5, env, hp, k=2.0)
    r_mad3 = detect_mad(ecg5, env, hp, k=3.0)
    print(f"  seed={seed}: MAD k=2 → {len(r_mad2)} (短RR={short_rr(r_mad2)})  "
          f"MAD k=3 → {len(r_mad3)} (短RR={short_rr(r_mad3)})")

# ===== 真实样本验证 =====
print(f"\n{'=' * 90}")
print("真实样本验证：MAD 稳健阈值")
print("=" * 90)

files = {
    "运动后(新)": ("samples/运动后.txt", 107, 53),
    "静息(新)": ("samples/静息.txt", 54, 27),
    "换右手(旧)": ("samples/心电api静息换右手.txt", 59, 30),
    "活动后(旧)": ("samples/心电api活动后.txt", 72, 36),
    "静息(旧)": ("samples/心电api静息.txt", 59, 30),
}

print(f"\n{'文件':<14} {'HV':<5} {'期望':<5} | {'MADk=2 R/HR/短':<16} | {'MADk=3 R/HR/短':<16} | {'判定k=2':<8}")
print("-" * 80)

for label, (path, hv_hr, expected_r) in files.items():
    ecg = load_ecg(path)
    env, hp = compute_envelope(ecg)

    r2 = detect_mad(ecg, env, hp, k=2.0)
    r3 = detect_mad(ecg, env, hp, k=3.0)

    hr2 = compute_hr(r2)
    hr3 = compute_hr(r3)

    diff2 = abs(hr2 - hv_hr) if hr2 > 0 and hv_hr > 0 else 999
    verdict = "✅准确" if diff2 < 15 else ("⚠改善" if hr2 > 0 else "❌")

    print(f"{label:<14} {hv_hr:<5} {expected_r:<5} | "
          f"{len(r2):<2}/{hr2:<3}/{short_rr(r2):<2}         | "
          f"{len(r3):<2}/{hr3:<3}/{short_rr(r3):<2}         | {verdict}")
