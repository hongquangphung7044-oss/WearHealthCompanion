"""
深入分析：长间隔位置真的有 R 波吗？
对照 HeartVoice 的 107bpm，检查每个漏检段
"""
import re
import math

with open("samples/运动后.txt", "r", encoding="utf-8") as f:
    content = f.read()
raw_match = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL)
ecg = [int(x) for x in raw_match.group(1).split() if x.lstrip('-').isdigit()]
sr = 500

# 复现算法（同上，省略到 envelope）
baseline = [0.0] * len(ecg)
half_win = sr // 2
for i in range(len(ecg)):
    lo = max(0, i - half_win)
    hi = min(len(ecg), i + half_win)
    baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
high_passed = [ecg[i] - baseline[i] for i in range(len(ecg))]
gradient = [0.0] * len(ecg)
for i in range(len(ecg)):
    prev = high_passed[max(0, i-1)]
    nxt = high_passed[min(len(ecg)-1, i+1)]
    gradient[i] = abs(nxt - prev) / 2.0
smooth_win = int(sr * 0.150)
envelope = [0.0] * len(ecg)
for i in range(len(ecg)):
    lo = max(0, i - smooth_win // 2)
    hi = min(len(ecg), i + smooth_win // 2 + 1)
    envelope[i] = sum(gradient[lo:hi]) / (hi - lo)

seg_len = sr * 4
thresholds = [0.0] * len(ecg)
for seg_start in range(0, len(envelope), seg_len):
    seg_end = min(len(envelope), seg_start + seg_len)
    seg_size = seg_end - seg_start
    if seg_size <= 0: continue
    seg_mean = sum(envelope[seg_start:seg_end]) / seg_size
    var_sum = sum((envelope[i] - seg_mean)**2 for i in range(seg_start, seg_end))
    seg_std = math.sqrt(var_sum / seg_size)
    seg_thr = seg_mean + seg_std * 2.0
    for i in range(seg_start, seg_end):
        thresholds[i] = seg_thr

# 找出每个 4 秒分段的阈值和 envelope 峰值
print("===== 各 4 秒分段阈值与 envelope 峰值 =====")
for seg_start in range(0, len(envelope), seg_len):
    seg_end = min(len(envelope), seg_start + seg_len)
    seg_env = envelope[seg_start:seg_end]
    seg_thr = thresholds[seg_start]
    env_max = max(seg_env)
    env_median = sorted(seg_env)[len(seg_env)//2]
    ratio = env_max / seg_thr if seg_thr > 0 else 0
    print(f"  段 {seg_start/sr:.1f}-{seg_end/sr:.1f}s: 阈值={seg_thr:.2f}, envMax={env_max:.2f}, 中位数={env_median:.2f}, max/thr={ratio:.2f}")

# 看长间隔处：每个长间隔中点附近的 envelope 值
# 重做 R 峰检测拿到 effective
refractory = sr // 5
check_range = sr // 50
r_peaks = []
last_peak = -refractory * 2
global_floor = 3.0
for i in range(len(envelope)):
    thr = max(thresholds[i], global_floor)
    if envelope[i] < thr: continue
    lo = max(0, i - check_range)
    hi = min(len(envelope), i + check_range + 1)
    is_max = True
    for j in range(lo, hi):
        if j != i and envelope[j] >= envelope[i]:
            if j < i: is_max = False; break
    if not is_max: continue
    if (i - last_peak) < refractory: continue
    r_lo = max(0, i - sr // 40)
    r_hi = min(len(high_passed), i + sr // 40)
    best_idx = i
    best_val = 0.0
    for j in range(r_lo, r_hi):
        if abs(high_passed[j]) > best_val:
            best_val = abs(high_passed[j])
            best_idx = j
    r_peaks.append(best_idx)
    last_peak = best_idx

# 回溯补检
if len(r_peaks) >= 5:
    extra = []
    for k in range(1, len(r_peaks)):
        rr = r_peaks[k] - r_peaks[k-1]
        rs = max(0, k-4)
        re_ = min(len(r_peaks), k+4)
        recent = []
        for m in range(rs+1, re_):
            if m == k: continue
            recent.append(r_peaks[m] - r_peaks[m-1])
        if len(recent) < 3: continue
        avg = sum(recent) / len(recent)
        if avg < 1.0: continue
        if rr > avg * 1.66:
            mid = (r_peaks[k-1] + r_peaks[k]) // 2
            mid_thr = thresholds[mid] if 0 <= mid < len(thresholds) else global_floor
            back_thr = max(mid_thr, global_floor) * 0.5
            ss = r_peaks[k-1] + refractory
            se = r_peaks[k] - refractory
            if se <= ss: continue
            best_b = -1
            best_bv = back_thr
            for j in range(ss, se+1):
                if j < 0 or j >= len(envelope): continue
                if envelope[j] > best_bv:
                    bl = max(0, j - check_range)
                    bh = min(len(envelope), j + check_range + 1)
                    is_m = True
                    for m in range(bl, bh):
                        if m != j and envelope[m] > envelope[j]:
                            is_m = False; break
                    if is_m:
                        best_bv = envelope[j]
                        best_b = j
            if best_b >= 0:
                extra.append(best_b)
    r_peaks.extend(extra)
    r_peaks.sort()

effective = r_peaks  # 无噪声段
print(f"\n===== R 峰位置（共 {len(effective)} 个）=====")
for i, p in enumerate(effective):
    t = p / sr
    print(f"  #{i}: t={t:.3f}s (样本 {p}), envelope={envelope[p]:.2f}, thr={thresholds[p]:.2f}, 比值={envelope[p]/thresholds[p]:.2f}")

print(f"\n===== 长间隔（>800ms）漏检诊断 =====")
for i in range(1, len(effective)):
    interval_ms = (effective[i] - effective[i-1]) * 1000 / sr
    if interval_ms > 800:
        gap_start = effective[i-1]
        gap_end = effective[i]
        gap_env = envelope[gap_start:gap_end]
        gap_max = max(gap_env)
        gap_max_idx = gap_start + gap_env.index(gap_max)
        gap_thr = thresholds[gap_start]
        ratio = gap_max / gap_thr if gap_thr > 0 else 0
        print(f"  间隔 {effective[i-1]/sr:.3f}s→{effective[i]/sr:.3f}s ({interval_ms:.0f}ms): gap内最大envelope={gap_max:.2f} at t={gap_max_idx/sr:.3f}s, 阈值={gap_thr:.2f}, 比值={ratio:.2f}")
        if ratio < 1.0:
            print(f"    → 漏检原因: gap 内最高峰 {gap_max:.2f} < 阈值 {gap_thr:.2f}（阈值太高）")
        else:
            print(f"    → gap 内有超过阈值的峰，但未被检出（可能局部最大/不应期问题）")
