"""
验证 ECG_diagnostic_20260718_212640.txt 的真实信号质量。
数据是 ADC 原始值（~18万含 DC），需先去 DC 再跑本地算法。
"""
import re
import math
from verify_red import compute_envelope


def load_adc_ecg(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    raw = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL).group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]


def detect_kstd(ecg, env, hp, sr=500, k=1.7, floor=3.0, seg_sec=4):
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
    refine_samples = sr // 20  # ±50ms (v5)
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
        r_lo, r_hi = max(0, i - refine_samples), min(len(hp), i + refine_samples)
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
                    r_lo, r_hi = max(0, best_b - refine_samples), min(len(hp), best_b + refine_samples)
                    r_idx, r_val = best_b, 0.0
                    for j in range(r_lo, r_hi):
                        if abs(hp[j]) > r_val:
                            r_val = abs(hp[j])
                            r_idx = j
                    extra.append(r_idx)
        r_peaks.extend(extra)
        r_peaks.sort()
    return r_peaks


def stats(ecg, r_peaks, sr=500):
    # 去基线后 R 波振幅（相对值，≈mV×1000）
    baseline_win = sr
    hp = []
    for i in range(len(ecg)):
        lo = max(0, i - baseline_win // 2)
        hi = min(len(ecg), i + baseline_win // 2)
        base = sum(ecg[lo:hi]) / (hi - lo)
        hp.append(ecg[i] - base)
    r_amps = [abs(hp[p]) for p in r_peaks if 0 <= p < len(hp)]
    r_amp_mean = sum(r_amps) / len(r_amps) if r_amps else 0
    # RR
    rr = [int((r_peaks[i] - r_peaks[i-1]) * 1000.0 / sr) for i in range(1, len(r_peaks))]
    rr_valid = [x for x in rr if 300 <= x <= 2500]
    if len(rr_valid) >= 3:
        mean = sum(rr_valid) / len(rr_valid)
        med = sorted(rr_valid)[len(rr_valid) // 2]
        hr = int(60000.0 / med) if med > 0 else 0
        sdnn = math.sqrt(sum((x - mean) ** 2 for x in rr_valid) / len(rr_valid))
        cv = sdnn / mean if mean > 0 else 0
        short_long = sum(1 for i in range(1, len(rr_valid)) if rr_valid[i] < mean * 0.7 and rr_valid[i-1] > mean * 1.3)
    else:
        hr = sdnn = cv = short_long = 0
    # 逐秒 RMS
    seg_rms = []
    for s in range(30):
        seg = hp[s*sr:(s+1)*sr]
        if seg:
            rms = math.sqrt(sum(x*x for x in seg) / len(seg)) / 1000.0  # 转 mV
            seg_rms.append(rms)
    noise_segs = sum(1 for r in seg_rms if r < 0.10)
    return {
        'r_count': len(r_peaks),
        'r_amp_mv': r_amp_mean / 1000.0,
        'hr': hr,
        'sdnn': sdnn,
        'cv': cv,
        'short_long_pairs': short_long,
        'seg_rms_min': min(seg_rms) if seg_rms else 0,
        'seg_rms_max': max(seg_rms) if seg_rms else 0,
        'noise_segs': noise_segs,
        'rr_valid': rr_valid,
    }


ecg = load_adc_ecg("samples/ECG_diagnostic_20260718_212640.txt")
print(f"数据点数: {len(ecg)}")
print(f"ADC 范围: min={min(ecg)} max={max(ecg)} (DC 偏移约 {sum(ecg)//len(ecg)})")
print()

env, hp = compute_envelope(ecg)
r = detect_kstd(ecg, env, hp)
s = stats(ecg, r)

print("=" * 60)
print("本地算法验证结果（v5: k=1.7 + ±50ms 精修）")
print("=" * 60)
print(f"R 波检测: {s['r_count']} 个")
print(f"R 波平均振幅: {s['r_amp_mv']:.2f} mV (去 DC 后)")
print(f"平均心率: {s['hr']} bpm")
print(f"SDNN: {s['sdnn']:.1f} ms")
print(f"RR 变异系数: {s['cv']:.3f} ({'轻度不齐' if 0.05 < s['cv'] < 0.15 else '规律' if s['cv'] <= 0.05 else '不齐'})")
print(f"短-长 RR 配对: {s['short_long_pairs']} 个")
print(f"逐秒 RMS 范围: {s['seg_rms_min']:.3f} - {s['seg_rms_max']:.3f} mV")
print(f"噪声段数 (RMS<0.10): {s['noise_segs']} / 30")
print()

# 对照诊断包里的本地重建结果
print("=" * 60)
print("对照诊断包 [算法提取后的结构化特征] 段")
print("=" * 60)
print("诊断包: R波=31, HR=73bpm, SDNN=96.7, CV=0.122, 短长配对=2, 信号质量=1.00")
print(f"本次验证: R波={s['r_count']}, HR={s['hr']}, SDNN={s['sdnn']:.1f}, CV={s['cv']:.3f}, 短长配对={s['short_long_pairs']}")
print()

# 判断信号质量
print("=" * 60)
print("信号质量评估")
print("=" * 60)
adc_range = max(ecg) - min(ecg)
print(f"ADC 原始值范围: {adc_range} (含 DC 偏移，DS 误判为'振幅异常巨大')")
print(f"去 DC 后 R 波振幅: {s['r_amp_mv']:.2f} mV (wrist ECG 正常 0.5-1.5mV)")
print(f"噪声段占比: {s['noise_segs']}/30 = {s['noise_segs']/30*100:.0f}%")
r_amp_ok = 0.3 <= s['r_amp_mv'] <= 2.0
noise_ok = s['noise_segs'] <= 5
r_count_ok = 25 <= s['r_count'] <= 45
print(f"R 波振幅正常: {'✅' if r_amp_ok else '❌'}")
print(f"噪声段正常: {'✅' if noise_ok else '❌'}")
print(f"R 波数量正常: {'✅' if r_count_ok else '❌'}")
