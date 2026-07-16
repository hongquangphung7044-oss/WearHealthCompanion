"""
模拟本地 EcgFeatureExtractor.detectRPeaks 在运动后.txt 上的行为
定位 R 波漏检 60% 的根因
"""
import re
import math

# 读运动后.txt 的原始 ECG 数据
with open("samples/运动后.txt", "r", encoding="utf-8") as f:
    content = f.read()

raw_match = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL)
raw_text = raw_match.group(1)
ecg = [int(x) for x in raw_text.split() if x.lstrip('-').isdigit()]
sr = 500
print(f"样本数: {len(ecg)}, 时长: {len(ecg)/sr:.1f}s")
print(f"原始值范围: {min(ecg)}~{max(ecg)}")

# ===== 1. 去基线漂移（1秒窗口移动平均）=====
baseline = [0.0] * len(ecg)
half_win = sr // 2
for i in range(len(ecg)):
    lo = max(0, i - half_win)
    hi = min(len(ecg), i + half_win)
    baseline[i] = sum(ecg[lo:hi]) / (hi - lo)
high_passed = [ecg[i] - baseline[i] for i in range(len(ecg))]

# ===== 2. |梯度| =====
gradient = [0.0] * len(ecg)
for i in range(len(ecg)):
    prev = high_passed[max(0, i-1)]
    nxt = high_passed[min(len(ecg)-1, i+1)]
    gradient[i] = abs(nxt - prev) / 2.0

# ===== 3. 150ms boxcar 平滑 =====
smooth_win = int(sr * 0.150)  # 75
envelope = [0.0] * len(ecg)
for i in range(len(ecg)):
    lo = max(0, i - smooth_win // 2)
    hi = min(len(ecg), i + smooth_win // 2 + 1)
    envelope[i] = sum(gradient[lo:hi]) / (hi - lo)

# ===== 4. 分段自适应阈值（4秒一段）=====
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

global_floor = 3.0

# ===== 5. R 峰检测（阈值+局部最大+200ms不应期+精修）=====
refractory = sr // 5  # 100 样本 = 200ms
check_range = sr // 50  # 10 样本 = ±10ms
r_peaks = []
last_peak = -refractory * 2
for i in range(len(envelope)):
    thr = max(thresholds[i], global_floor)
    if envelope[i] < thr: continue
    lo = max(0, i - check_range)
    hi = min(len(envelope), i + check_range + 1)
    is_max = True
    for j in range(lo, hi):
        if j != i and envelope[j] >= envelope[i]:
            if j < i:
                is_max = False
                break
    if not is_max: continue
    if (i - last_peak) < refractory: continue
    # 精修
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

print(f"\n检测前回溯补检: {len(r_peaks)} 个 R 波")

# ===== 6. 回溯补检 =====
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
                            is_m = False
                            break
                    if is_m:
                        best_bv = envelope[j]
                        best_b = j
            if best_b >= 0:
                extra.append(best_b)
    r_peaks.extend(extra)
    r_peaks.sort()

print(f"回溯补检后: {len(r_peaks)} 个 R 波")

# ===== 7. 噪声段剔除 =====
duration_sec = len(ecg) / sr
def extract_segments(r_peaks_list):
    segs = []
    for s in range(int(duration_sec)):
        start = int(s * sr)
        end = int((s+1) * sr)
        if end > len(ecg): break
        seg_data = ecg[start:end]
        seg_mean = sum(seg_data) / len(seg_data)
        seg_dev = [v - seg_mean for v in seg_data]
        rms = math.sqrt(sum(d*d for d in seg_dev) / len(seg_dev)) / 1000  # mV
        cnt = sum(1 for p in r_peaks_list if start <= p < end)
        segs.append({"start": s, "end": s+1, "rms": rms, "r_count": cnt})
    return segs

segs_raw = extract_segments(r_peaks)
noise_ranges = [(s["start"]*sr, s["end"]*sr) for s in segs_raw if s["rms"] < 0.10]
effective = []
for p in r_peaks:
    in_noise = any(rs <= p < re_ for rs, re_ in noise_ranges)
    if not in_noise:
        effective.append(p)

print(f"噪声段剔除后（effectiveRPeaks）: {len(effective)} 个 R 波")
print(f"噪声段数: {len(noise_ranges)}")

# ===== 8. RR 间期分析 =====
rr = []
for i in range(1, len(effective)):
    rr_ms = (effective[i] - effective[i-1]) * 1000 / sr
    rr.append(rr_ms)
print(f"\nRR 间期数: {len(rr)}")
if rr:
    print(f"RR 范围: {min(rr):.0f}~{max(rr):.0f}ms")
    rr_sorted = sorted(rr)
    median = rr_sorted[len(rr_sorted)//2]
    print(f"RR 中位数: {median:.0f}ms (对应 {60000/median:.0f}bpm)")
    print(f"RR 序列（前 25 个）: {[int(x) for x in rr[:25]]}")

# ===== 9. filterEctopicBeats 模拟（±30% 中位数）=====
if rr:
    filtered = [r for r in rr if abs(r - median) / median <= 0.30]
    print(f"\nfilterEctopicBeats 后: {len(filtered)} 个 RR（原 {len(rr)}）")
    if filtered:
        avg_hr = 60000 / (sum(filtered) / len(filtered))
        print(f"过滤后平均心率: {avg_hr:.0f}bpm")
        print(f"过滤后 RR（前 10）: {[int(x) for x in filtered[:10]]}")

# ===== 10. 关键诊断：R 波位置间隔分布 =====
print(f"\n===== R 波位置间隔分析（诊断漏检）=====")
if len(effective) >= 2:
    intervals = [effective[i] - effective[i-1] for i in range(1, len(effective))]
    intervals_ms = [i * 1000 / sr for i in intervals]
    # 应该的 RR：107bpm → 560ms → 280 样本
    expected_rr_ms = 60000 / 107
    expected_rr_samples = expected_rr_ms * sr / 1000
    print(f"期望 RR（107bpm）: {expected_rr_ms:.0f}ms = {expected_rr_samples:.0f}样本")
    print(f"实际间隔范围: {min(intervals_ms):.0f}~{max(intervals_ms):.0f}ms")
    print(f"实际间隔（前 20）: {[int(x) for x in intervals[:20]]}")
    # 统计间隔分布
    short = sum(1 for x in intervals_ms if x < 400)  # <400ms 异常短（误检）
    normal = sum(1 for x in intervals_ms if 400 <= x <= 800)  # 400-800ms 正常（75-150bpm）
    long_ = sum(1 for x in intervals_ms if x > 800)  # >800ms 异常长（漏检）
    print(f"间隔分类: 短(<400ms,误检)={short}, 正常(400-800ms)={normal}, 长(>800ms,漏检)={long_}")
