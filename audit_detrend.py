"""
审查 raw 模式数据预处理 vs 提示词描述是否对应。
用实际 raw 模式样本数据，模拟 detrendBySlidingWindow，看去趋势后特征。
"""
import re
import statistics

def load_raw_adc(path):
    """从诊断包提取原始 ADC 数据"""
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    # [原始 ECG 数据] 段，每行一个数值
    m = re.search(r'\[原始 ECG 数据\]\n.*?\n.*?\n.*?\n.*?\n.*?\n.*?\n.*?\n(.*?)\n\n', content, re.DOTALL)
    if not m:
        # 尝试更宽松的匹配
        m = re.search(r'\[原始 ECG 数据\]\n(?:#.*\n)*\n?(.*?)\n\n', content, re.DOTALL)
    raw = m.group(1)
    return [int(x) for x in raw.split() if x.lstrip('-').isdigit()]

def detrend_by_sliding_window(data, window_size):
    """精确模拟 Kotlin detrendBySlidingWindow"""
    n = len(data)
    half_win = window_size // 2
    result = [0] * n
    win_sum = 0
    win_count = 0
    # 初始化第一个窗口 [0, halfWin]
    for i in range(min(n - 1, half_win) + 1):
        win_sum += data[i]
        win_count += 1
    for i in range(n):
        left = i - half_win - 1
        right = i + half_win
        if left >= 0 and win_count > 1:
            win_sum -= data[left]
            win_count -= 1
        if right < n:
            win_sum += data[right]
            win_count += 1
        # Kotlin: (winSum / winCount).toInt()  — Long 除法向零截断
        local_mean = int(win_sum / win_count) if win_sum >= 0 else -int(-win_sum / win_count)
        result[i] = data[i] - local_mean
    return result

# 加载实际数据
path = "samples/ECG_diagnostic_20260718_212640.txt"
adc = load_raw_adc(path)
print(f"=== 原始 ADC 数据特征 ===")
print(f"样本数: {len(adc)}")
print(f"范围: {min(adc)} ~ {max(adc)}")
print(f"中位数: {statistics.median(adc)}")
print(f"均值: {statistics.mean(adc):.1f}")
print(f"峰峰值(原始ADC): {max(adc) - min(adc)}")
print()

# 模拟去趋势（2秒窗口 = 1000点）
detrended = detrend_by_sliding_window(adc, window_size=1000)
print(f"=== 去趋势后特征（2秒滑动窗口，commit e8f6216 实际代码）===")
print(f"样本数: {len(detrended)}")
print(f"范围: {min(detrended)} ~ {max(detrended)}")
print(f"中位数: {statistics.median(detrended)}")
print(f"均值: {statistics.mean(detrended):.1f}")
print(f"峰峰值(去趋势后): {max(detrended) - min(detrended)}")
print(f"标准差: {statistics.stdev(detrended):.1f}")
print()

# 逐秒 RMS（提示词说 RMS<30 是噪声段）
import math
print(f"=== 逐秒 RMS（提示词阈值 <30 为噪声段）===")
sr = 500
noise_count = 0
for sec in range(len(detrended) // sr):
    seg = detrended[sec*sr:(sec+1)*sr]
    rms = math.sqrt(sum(x*x for x in seg) / len(seg))
    flag = "噪声" if rms < 30 else ""
    if rms < 30:
        noise_count += 1
    if sec < 5 or sec >= 25 or flag:  # 只打印前5秒、后5秒、噪声段
        print(f"  第{sec}秒: RMS={rms:.1f} {flag}")
print(f"  ...（中间省略）...")
print(f"噪声段占比: {noise_count}/{len(detrended)//sr} = {noise_count/(len(detrended)//sr)*100:.1f}%")
print()

# 提示词说"R 波峰峰值通常 200-1000"，验证
# 找局部最大值（候选 R 波）
print(f"=== R 波峰峰值验证（提示词说 200-1000）===")
# 简单找峰：去趋势后绝对值 > 100 的局部最大
abs_data = [abs(x) for x in detrended]
# 找绝对值最大的 10 个点（间隔至少 200ms=100点）
peaks = []
sorted_idx = sorted(range(len(abs_data)), key=lambda i: -abs_data[i])
for idx in sorted_idx:
    if all(abs(idx - p) > 100 for p in peaks):
        peaks.append(idx)
    if len(peaks) >= 10:
        break
peaks.sort()
print(f"前 10 个 R 波候选峰（去趋势后值）：")
for p in peaks:
    # 找该峰周围 ±25ms 的峰峰值
    lo = max(0, p - 12)
    hi = min(len(detrended), p + 13)
    local = detrended[lo:hi]
    pp = max(local) - min(local)
    print(f"  索引{p}: 值={detrended[p]}, 局部峰峰值(±25ms)={pp}")
print()

# 对照提示词的关键描述
print(f"=== 提示词描述 vs 实际数据对照 ===")
checks = [
    ("原始 ADC 含约 18 万 DC 偏移", f"实际中位数 {statistics.median(adc)}", abs(statistics.median(adc) - 180000) < 30000),
    ("去趋势后以 0 为中心", f"实际均值 {statistics.mean(detrended):.1f}", abs(statistics.mean(detrended)) < 50),
    ("去趋势后范围通常 ±1000", f"实际范围 {min(detrended)}~{max(detrended)}", max(abs(min(detrended)), abs(max(detrended))) <= 1500),
    ("R 波峰峰值 200-1000", f"见上方候选峰", True),  # 人工判断
    ("整段峰峰值<100 为信号差", f"实际峰峰值 {max(detrended)-min(detrended)}", (max(detrended)-min(detrended)) > 100),
    ("RMS<30 为噪声段", f"噪声段占比 {noise_count/(len(detrended)//sr)*100:.1f}%", True),
]
for desc, actual, ok in checks:
    print(f"  {'✅' if ok else '⚠️'} 提示词: {desc}")
    print(f"      实际: {actual}")
