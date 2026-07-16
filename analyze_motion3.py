"""
深入：0.92 百分位 + 形态验证下，运动后漏检位置分析
"""
import re, math
exec(open("test_morph.py").read().split("files =")[0])  # 复用函数

ecg = load_ecg("samples/运动后.txt")
env, hp = compute_envelope(ecg)
r = detect_with_morphology(ecg, env, hp, 500, 0.92)
print(f"检出 {len(r)} 个 R 波（期望 53）")

# 每个检出的 R 峰 envelope 和 hp 值
print("\n检出 R 峰详情:")
for i, p in enumerate(r[:10]):
    t = p / 500
    print(f"  #{i}: t={t:.3f}s env={env[p]:.2f} |hp|={abs(hp[p]):.1f}")

# 找长间隔（漏检位置）
print("\n长间隔漏检分析:")
for i in range(1, len(r)):
    interval_ms = (r[i] - r[i-1]) * 1000 / 500
    if interval_ms > 800:
        gap_start, gap_end = r[i-1], r[i]
        # gap 内所有超过阈值的点
        seg_thr = max(percentile(sorted(env[max(0,gap_start-2000):gap_start+2000]), 0.92), 3.0)
        # 找 gap 内 envelope 局部峰
        gap_env = env[gap_start+100:gap_end-100]  # 排除边界
        peaks_in_gap = []
        for j in range(gap_start+100, gap_end-100):
            if env[j] < seg_thr: continue
            # 局部最大
            lo, hi = max(0, j-10), min(len(env), j+11)
            is_max = True
            for m in range(lo, hi):
                if m != j and env[m] > env[j]: is_max = False; break
            if is_max:
                # 形态验证
                lf = max(0, j-25); rf = min(len(hp)-1, j+25)
                morph_ok = abs(hp[j]) >= abs(hp[lf])*1.3 and abs(hp[j]) >= abs(hp[rf])*1.3
                peaks_in_gap.append((j, env[j], seg_thr, abs(hp[j]), abs(hp[lf]), abs(hp[rf]), morph_ok))
        print(f"  间隔 {r[i-1]/500:.2f}s→{r[i]/500:.2f}s ({interval_ms:.0f}ms), thr={seg_thr:.2f}")
        for pk in peaks_in_gap[:5]:
            t = pk[0]/500
            print(f"    gap内峰 t={t:.3f}s env={pk[1]:.2f} thr={pk[2]:.2f} |hp|={pk[3]:.1f} 左={pk[4]:.1f} 右={pk[5]:.1f} 形态OK={pk[6]}")
        if not peaks_in_gap:
            # 看看 gap 内最高 envelope
            gap_max = max(env[gap_start:gap_end])
            gap_max_idx = gap_start + env[gap_start:gap_end].index(gap_max)
            print(f"    无超阈值峰, gap内最高 env={gap_max:.2f} at t={gap_max_idx/500:.3f}s (thr={seg_thr:.2f})")

# 关键诊断：运动后 RR 应 ~560ms，看检出 RR 分布
rr = [(r[i]-r[i-1])*1000/500 for i in range(1, len(r))]
print(f"\n检出 RR 间期分布:")
print(f"  <400ms(误检): {sum(1 for x in rr if x<400)}")
print(f"  400-700ms(107bpm对应560ms): {sum(1 for x in rr if 400<=x<=700)}")
print(f"  700-1000ms: {sum(1 for x in rr if 700<x<=1000)}")
print(f"  >1000ms(漏检): {sum(1 for x in rr if x>1000)}")
print(f"  RR 前15: {[int(x) for x in rr[:15]]}")
