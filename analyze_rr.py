"""
深入分析活动后文件心率=0 的根因。
HeartVoice 报 72bpm，本地算法报 0bpm，但检出 25 个 R 波。
心率=0 说明 RR 间期过滤逻辑把所有 RR 都剔除了。
"""
import re

with open("samples/心电api活动后.txt", "r", encoding="utf-8") as f:
    content = f.read()

# 提取本地算法的 RR 间期序列
rr_match = re.search(r'RR间期序列.*?:(.+)', content)
if rr_match:
    rr_text = rr_match.group(1)
    # 解析 RR 间期（去掉 [*] 标记）
    rrs = []
    for token in rr_text.replace('[', '').replace(']', '').replace('*', '').split():
        try:
            rrs.append(int(token))
        except:
            pass
    print(f"活动后文件 RR 间期序列 ({len(rrs)} 个):")
    print(f"  原始 RR: {rrs}")

    # 分析 RR 间期分布
    if rrs:
        mean_rr = sum(rrs) / len(rrs)
        median_rr = sorted(rrs)[len(rrs)//2]
        print(f"\n  RR 均值: {mean_rr:.0f}ms (心率 {60000/mean_rr:.0f}bpm)")
        print(f"  RR 中位数: {median_rr}ms (心率 {60000/median_rr:.0f}bpm)")
        print(f"  RR 范围: {min(rrs)}~{max(rrs)}ms")

        # 看 computeHeartRateStats 的过滤逻辑
        # 正常 RR 范围 400-1500ms (40-150bpm)
        valid_rr = [rr for rr in rrs if 400 <= rr <= 1500]
        print(f"\n  通过 400-1500ms 过滤的 RR: {len(valid_rr)}/{len(rrs)}")
        print(f"  有效 RR: {valid_rr}")

        # filterEctopicBeats 的逻辑：偏离中位数 ±30% 的丢弃
        if valid_rr:
            med = sorted(valid_rr)[len(valid_rr)//2]
            filtered = [rr for rr in valid_rr if abs(rr - med) / med <= 0.3]
            print(f"\n  中位数: {med}ms")
            print(f"  通过 ±30% 过滤的 RR: {len(filtered)}/{len(valid_rr)}")
            print(f"  过滤后 RR: {filtered}")
            if filtered:
                hr = 60000 / (sum(filtered)/len(filtered))
                print(f"  过滤后心率: {hr:.0f}bpm")

        # 检查 RR 间期的异常模式
        print(f"\n  RR 间期模式分析:")
        for i, rr in enumerate(rrs):
            hr = 60000/rr if rr > 0 else 0
            flag = ""
            if rr < 400: flag = " ← 过短(<400ms)"
            elif rr > 1500: flag = " ← 过长(>1500ms)"
            elif 400 <= rr <= 1500:
                dev = abs(rr - median_rr) / median_rr if median_rr > 0 else 0
                if dev > 0.3: flag = f" ← 偏离中位数 {dev*100:.0f}%"
            print(f"    RR[{i}]: {rr}ms ({hr:.0f}bpm){flag}")

# R 波位置分析
rpos_match = re.search(r'R波位置\(样本索引\):(.+)', content)
if rpos_match:
    positions = [int(x) for x in rpos_match.group(1).split(',')]
    print(f"\n  R 波位置 ({len(positions)} 个):")
    print(f"    首 5: {positions[:5]}")
    print(f"    末 5: {positions[-5:]}")
    # 位置间隔
    intervals = [positions[i+1] - positions[i] for i in range(len(positions)-1)]
    print(f"    位置间隔（样本）: {intervals[:10]}...")
    print(f"    位置间隔（ms）: {[iv*1000//500 for iv in intervals[:10]]}...")

# 看 HeartVoice 报的 72bpm 对应的 RR
print(f"\n  HeartVoice 报 72bpm → RR ≈ {60000//72}ms")
print(f"  本地算法 RR 中位数应为 ~833ms，但实际中位数偏离太多")
