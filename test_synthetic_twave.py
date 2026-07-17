"""验证含大 T 波的负向 R 波信号能否触发节律误判（RED 前提）
223211 的 T/R=1.09，T 波比 R 波大，可能被误检为第二个 R 波
"""
import math
import random


def synthetic_ecg_with_twave(duration_sec, r_peak_times, r_amp_mv=-0.5, t_amp_mv=0.55,
                              noise_level=0.03, seed=42, sr=500):
    """负向 R 波 + 大 T 波（T/R>1，模拟 223211）"""
    total = int(duration_sec * sr)
    rng = random.Random(seed)
    sigma = 0.01
    sigma_sq2 = 2 * sigma * sigma
    t_sigma = 0.04
    t_sigma_sq2 = 2 * t_sigma * t_sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
            # T 波：R 波后 300ms，正向（与负向 R 波反极性，模拟 223211 的 T 波）
            t_dt = t - rT - 0.3
            v += t_amp_mv * math.exp(-(t_dt * t_dt) / t_sigma_sq2)
        v += (rng.random() - 0.5) * 2 * noise_level
        data.append(int(v * 1000))
    return data


def compute_envelope(ecg, sr=500):
    baseline = [0.0] * len(ecg)
    half_win = sr // 2
    for i in range(len(ecg)):
        lo, hi = max(0, i - half_win), min(len(ecg), i + half_win)
        baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
    hp = [ecg[i] - baseline[i] for i in range(len(ecg))]
    grad = [0.0] * len(ecg)
    for i in range(len(ecg)):
        prev, nxt = hp[max(0, i - 1)], hp[min(len(ecg) - 1, i + 1)]
        grad[i] = abs(nxt - prev) / 2.0
    sw = int(sr * 0.150)
    env = [0.0] * len(ecg)
    for i in range(len(ecg)):
        lo, hi = max(0, i - sw // 2), min(len(ecg), i + sw // 2 + 1)
        env[i] = sum(grad[lo:hi]) / (hi - lo)
    return env, hp


def detect_v5(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
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
        thr = mean + std * k
        for i in range(ss, se):
            thresholds[i] = thr
    refractory = sr // 5
    check_range = sr // 50
    refine_range = sr // 20
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
        r_lo, r_hi = max(0, i - refine_range), min(len(hp), i + refine_range + 1)
        best_idx, best_val = i, 0.0
        for j in range(r_lo, r_hi):
            if abs(hp[j]) > best_val:
                best_val = abs(hp[j])
                best_idx = j
        r_peaks.append(best_idx)
        last_peak = best_idx
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
                ss, se = r_peaks[k_idx - 1] + refractory, r_peaks[k_idx] - refractory
                if se <= ss:
                    continue
                best_b, best_bv = -1, back_thr
                for j in range(ss, se + 1):
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
                    r_lo, r_hi = max(0, best_b - refine_range), min(len(hp), best_b + refine_range + 1)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def filter_ectopic(rr, threshold=0.20):
    if len(rr) < 3:
        return rr[:]
    mean = sum(rr) / len(rr)
    if mean <= 0:
        return rr[:]
    filtered = [x for x in rr if abs(x - mean) / mean <= threshold]
    return filtered if filtered else rr[:]


def rhythm_features(rr_raw, use_clean=False):
    if len(rr_raw) < 4:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    rr_cv = filter_ectopic(rr_raw) if use_clean else rr_raw
    if len(rr_cv) < 4:
        return {"cv": 0, "ratio": 0, "slp": 0, "pattern": "数据不足"}
    mean = sum(rr_cv) / len(rr_cv)
    variance = sum((x - mean) ** 2 for x in rr_cv) / len(rr_cv)
    cv = math.sqrt(variance) / mean
    diffs = [rr_cv[i] - rr_cv[i+1] for i in range(len(rr_cv)-1)]
    sums = [rr_cv[i] + rr_cv[i+1] for i in range(len(rr_cv)-1)]
    dm, sm = sum(diffs)/len(diffs), sum(sums)/len(sums)
    sd1 = math.sqrt(sum((d-dm)**2 for d in diffs)/len(diffs)/2.0)
    sd2 = math.sqrt(sum((s-sm)**2 for s in sums)/len(sums)/2.0)
    ratio = sd1/sd2 if sd2 > 0.1 else 0
    mr = sum(rr_raw)/len(rr_raw)
    slp = sum(1 for i in range(len(rr_raw)-1) if rr_raw[i] < mr*0.8 and rr_raw[i+1] > mr*1.2)
    if cv < 0.05: p = "彗星形(规律)"
    elif cv < 0.15 and ratio < 0.5: p = "彗星形(规律)"
    elif cv < 0.15 and ratio >= 0.5: p = "鱼雷形(轻度不齐)"
    elif 0.15 <= cv <= 0.20 and ratio >= 0.7: p = "扇形(不规律)"
    elif cv > 0.20 and ratio >= 0.7: p = "扇形(疑似房颤)❌"
    elif slp >= 3 and cv < 0.20: p = "复杂形(疑似早搏)"
    else: p = "彗星形(规律)"
    return {"cv": cv, "ratio": ratio, "slp": slp, "pattern": p}


def main():
    r_times = []
    t = 0.5
    while t < 30.0:
        r_times.append(t)
        t += 0.769
    expected = len(r_times)
    print(f"合成: 78bpm, {expected} R波, 负向R -0.5mV, T波 +0.55mV (T/R=1.1 模拟223211)")

    for t_amp in [0.3, 0.55, 0.8]:
        ecg = synthetic_ecg_with_twave(30.0, r_times, r_amp_mv=-0.5, t_amp_mv=t_amp, noise_level=0.03)
        env, hp = compute_envelope(ecg)
        r_peaks = detect_v5(ecg, env, hp)
        rr = [int((r_peaks[i] - r_peaks[i-1]) * 1000 / 500) for i in range(1, len(r_peaks))]
        rr_valid = [x for x in rr if 300 <= x <= 2500]
        short = sum(1 for x in rr if 0 < x < 400)
        cur = rhythm_features(rr_valid, False)
        fix = rhythm_features(rr_valid, True)
        print(f"\nT振幅 {t_amp}mV (T/R={abs(t_amp/(-0.5)):.1f}): 检出{len(r_peaks)}(期望{expected}) 短RR={short}")
        print(f"  当前: CV={cur['cv']:.3f} ratio={cur['ratio']:.2f} SLP={cur['slp']} → {cur['pattern']}")
        print(f"  修复: CV={fix['cv']:.3f} ratio={fix['ratio']:.2f} SLP={fix['slp']} → {fix['pattern']}")


if __name__ == "__main__":
    main()
