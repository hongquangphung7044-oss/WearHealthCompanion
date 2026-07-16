"""
诊断 k=2.0 vs k=1.7 下 R 峰位置的精确差异，找出 RR 784→772 漂移的根因。
"""
import math
from java_random import JavaRandom, kotlin_synthetic_ecg
from verify_red import compute_envelope


def detect_kstd_debug(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
    """带诊断输出的 detect_kstd"""
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
        thr = max(mean + k * std, floor)
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
        # 精修 ±25ms
        r_lo, r_hi = max(0, i - sr // 40), min(len(hp), i + sr // 40)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append((best_idx, i))  # (精修后, 原始包络峰)
        last_peak = best_idx

    # 回溯补检
    if len(r_peaks) >= 5:
        extra = []
        for k_idx in range(1, len(r_peaks)):
            rr = r_peaks[k_idx][0] - r_peaks[k_idx - 1][0]
            rs, re_ = max(0, k_idx - 4), min(len(r_peaks), k_idx + 4)
            recent = [r_peaks[m][0] - r_peaks[m - 1][0] for m in range(rs + 1, re_) if m != k_idx]
            if len(recent) < 3:
                continue
            avg = sum(recent) / len(recent)
            if avg < 1.0:
                continue
            if rr > avg * 1.66:
                mid = (r_peaks[k_idx - 1][0] + r_peaks[k_idx][0]) // 2
                mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else floor
                back_thr = max(mid_thr, floor) * 0.5
                ss2, se2 = r_peaks[k_idx - 1][0] + refractory, r_peaks[k_idx][0] - refractory
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
                    extra.append((r_idx, best_b))
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


sr = 500
r_times = [i * 0.857 for i in range(35)]
ecg = kotlin_synthetic_ecg(30.0, r_times, r_amp_mv=1.0, noise_level=0.01, seed=42)
env, hp = compute_envelope(ecg, sr)

print("真实 R 波位置（理论）:", [int(t * sr) for t in r_times[:8]])
print()

for k in [2.0, 1.7]:
    r_peaks = detect_kstd_debug(ecg, env, hp, sr=sr, k=k)
    print(f"=== k={k} ===")
    print(f"检测到 {len(r_peaks)} 个 R 波")
    print(f"前 8 个 R 波 (精修后, 原始包络峰):")
    for i, (refined, raw) in enumerate(r_peaks[:8]):
        delta = refined - raw
        expected = int(r_times[i] * sr)
        drift = refined - expected
        print(f"  R[{i}]: refined={refined} raw={raw} (Δ={delta:+d}) | 期望={expected} 漂移={drift:+d} ({drift*2:+d}ms)")
    rr = [r_peaks[i][0] - r_peaks[i-1][0] for i in range(1, min(8, len(r_peaks)))]
    rr_ms = [x * 2 for x in rr]
    print(f"前 7 个 RR (ms): {rr_ms}")
    if rr_ms:
        print(f"RR min={min(rr_ms)} max={max(rr_ms)} range={max(rr_ms)-min(rr_ms)}")
    print()
