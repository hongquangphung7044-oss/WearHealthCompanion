# ECG 本地算法优化指南

> 本文档专门记录 WearHealthCompanion 项目中**单导联 ECG 本地特征提取算法的优化工作**。
> 目标读者：接手算法优化的 AI 或开发者。读完本文档应能理解算法架构、已知问题、优化方法论和约束规范。

---

## 0. TL;DR（接手前必读 30 秒）

| 项目 | 当前值 | 位置 |
|------|--------|------|
| **算法版本** | v7（精修二次不应期 300ms + T 波排除 300-450ms + PPG 兜底 + R 峰可靠性评估） | [EcgFeatureExtractor.kt:120-790](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt#L120-L790) |
| **最新 CI** | Build #138 / commit `7ab060c` ✅ BUILD SUCCESSFUL | [Actions](https://github.com/hongquangphung7044-oss/WearHealthCompanion/actions) |
| **R 波检测核心参数** | k=1.7, MWI=150ms, envelope 峰间距不应期=200ms, 精修=±50ms, **精修二次不应期=300ms**, **回溯补检 T 波排除=300-450ms**, 回溯触发=1.66×, 回溯阈值=0.5× | [L234-433](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt#L234-L433) |
| **R 峰可靠性评估** | 5 维度（极端 RR/有效 RR/RR 跳变/一致性/异常段），三档判定（可信/边缘/不可信），不可信时降级输出 | [L681-790](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt#L681-L790) |
| **节律判别策略** | 本地只测量（RR/CV/Poincaré/短长配对），判读归 DS；R 峰不可信时不传节律证据 | 见第 7.3 节 |
| **核心约束** | 不本地构建（只 `git diff --check` + Python 验证）；TDD；阈值要有依据；不回退 Build #59 BLE 修复 | 见第 6 节 |
| **配套主文档** | [README.md](../README.md)（项目总交接文档，顶部有自动构建状态） | — |

**最近 3 次算法演进**：
- v7 (commit `b463332`→`7ab060c`, 2026-07-17)：精修二次不应期 200ms→300ms，回溯补检 T 波排除窗口 200-450ms→300-450ms，新增 PPG 兜底 avgHr + min/max=0 提示。7 样本验证：5 个边缘升级为可信，无回归 → 见第 5 节 Bug 9-10
- v6 (commit `682cc6c`, 2026-07-17)：精修二次不应期验证 200ms + 回溯补检 T 波排除 200-450ms；新增 R 峰可靠性评估（5 维度三档降级）；节律判别 CV/ratio 改用 filterEctopicBeats 清洗后 RR，消除早搏短长 RR 导致的房颤误判 → 见第 5 节 Bug 8
- v5 (commit `94326ea`, 2026-07-16)：精修窗口 ±25ms→±50ms，修复 envelope 峰在 R 峰上升沿导致 HR range>10 → 见第 5 节 Bug 7

**接手第一步**：先读第 3 节算法架构，再读第 5 节 Bug 历史（避免重复踩坑），最后读第 7 节待优化方向。

---

## 1. 背景与目标

### 项目概述
WearHealthCompanion 是基于三星手表的 ECG 测量+分析应用。手表采集 30 秒单导联 ECG（500Hz，约 15000 点），本地提取特征后，通过三种路径之一进行分析：

| 路径 | analysisMethod | 说明 |
|------|----------------|------|
| HeartVoice 专业 API | `heartvoice` | 医疗级服务端算法，作"参考金标准" |
| DeepSeek V4-Flash 均衡档 | `ds_flash_balanced` | LLM 推理，思考强度=high |
| DeepSeek V4-Flash Max 档 | `ds_flash_max` | LLM 推理，思考强度=max，带 Tavily 联网检索 |

> **不用 DS Pro**，只用 V4-Flash 两档（继承约束）。

### 为什么需要优化本地算法
1. **DS 路径依赖本地特征**：DS 收到的是本地 DSP 提取的结构化文本（不是原始波形），本地算法漏检/误检会直接误导 LLM
2. **HeartVoice 路径的 minHr/maxHr**：API 不返回最低/最高心率，需本地 R-R 间期估算
3. **wrist ECG 信号质量差**：手腕佩戴的电极-皮肤界面不稳定，基线漂移+运动伪差远比临床心电图严重，通用算法在此场景退化

### 优化目标
让本地 `EcgFeatureExtractor` 在真实 wrist ECG（噪声大、基线漂移、接触不良）上的 R 波检测/心率/HRV 结果，尽可能接近 HeartVoice 专业 API 的返回值。差异点即改进方向。

---

## 2. 系统数据流

```
手表采集 ECG (500Hz × 30s = 15000 点, mV×1000 整数)
    │
    ├─→ [HeartVoice 路径] 原始波形上传 API → 服务端算法 → 返回聚合指标
    │                                                    (avgHr/QRS/PR/QTc/diagnosis)
    │
    ├─→ [DS 路径] EcgFeatureExtractor.extract() → toPromptText() → 文本特征
    │              └─→ DeepSeek API → JSON 报告
    │
    └─→ [导出诊断包] DiagnosticExporter.buildText() → .txt 文件
                      ├─ [原始 ECG 数据]
                      ├─ [HeartVoice API 返回]  (仅 heartvoice 路径)
                      ├─ [算法提取后的结构化特征] (本地算法，所有路径都重建)
                      └─ [AI 解读]               (仅 DS 路径)
```

**关键点**：`[算法提取后的结构化特征]` 段不管哪条路径都会重建本地算法结果，所以 HeartVoice 导出文件天然有"HeartVoice API vs 本地算法"的对照——这是优化的核心输入。

---

## 3. 本地算法架构（EcgFeatureExtractor）

**文件**：[shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt)

### 设计原则
1. 本地完成所有 DSP 工作（R 波检测、HRV、间期估测），不指望 LLM 算数字
2. 输出结构化文本（全局指标 + 逐秒分段表），token 量可控（约 1500~2500）
3. 间期估测标注误差范围（±15ms），让 DS 知道这是估测值
4. 噪声段明确标记，避免 DS 误判

### R 波检测链（detectRPeaks，Pan-Tompkins 风格）

```
原始 ECG (mV×1000)
    │
    ├─ 1. 去基线漂移：1秒窗口移动平均（等效高通 ~1Hz）
    │      → 消除呼吸/电极压力变化导致的慢漂移
    │
    ├─ 2. |梯度|：一阶中心差分绝对值
    │      → 突出 QRS 陡峭斜率，抑制平缓 P/T 波
    │
    ├─ 3. 150ms boxcar 平滑（MWI 移动窗口积分）
    │      → 形成包络，Pan-Tompkins 原版标准
    │      → 旧实现 50ms 太窄，一个 QRS 的 R+R' 切迹会产生双峰 → 虚假短 RR
    │      → 150ms 覆盖整个 QRS 复合波（病理可达 150ms），一个 QRS 只产生一个峰
    │
    ├─ 4. 分段自适应阈值：4秒一段，各自 mean+1.7×std
    │      → 解决局部噪声污染全局阈值（某段大运动伪差拉高 envStd → 干净段漏检）
    │      → 4秒覆盖约 4-5 个心动周期，统计稳健
    │      → 全局 floor=3.0 防纯静音段误判
    │      → k=1.7（非 2.0）：运动后肌电干扰让 envelope 右偏长尾，mean+2std 被长尾拉高
    │        → 真实 R 波 envelope 峰仅 0.85~1.11 倍阈值 → 漏检 60%（运动后.txt）
    │        → k 从 2.0 降到 1.7，让运动后边缘 R 波通过
    │      → k=1.7 是经验调参值（非临床金标准），依据 5 份真实 wrist ECG + 6 seed 合成信号
    │      → 不加形态验证：v3 形态验证（|hp| 侧翼 1.1 倍）误杀上升沿候选
    │        （envelope 峰在 R 峰前 40-50ms 上升沿，|hp| 在候选点低 → 误杀）
    │      → 误检控制改由噪声段掩码 + filterEctopicBeats 兜底
    │
    ├─ 5. R 峰检测：阈值 + 局部最大(±10ms) + 200ms 不应期
    │      → 不应期 200ms（Pan-Tompkins 原版，生理上 RR 不可能 <200ms）
    │      → 用 >= 防 plateau 双峰
    │
    ├─ 6. 精修：原始去基线信号 ±50ms 邻域找真正 R 峰
    │      → envelope 峰在 R 波上升沿最陡处，比真正 R 峰提前 ~30-50ms
    │      → ±25ms 够不到 R 峰，找到上升沿噪声局部最大值 → RR 漂移 ±12ms → HR range>10 失败
    │      → ±50ms 稳定命中 R 峰（Pan-Tompkins 原版标准窗口）
    │      → 关键：lastPeakIdx 必须用精修后的 bestIdx，否则不应期偏差累积
    │
    └─ 7. 回溯补检（Pan-Tompkins 核心）：RR > 1.66×近期均值时半阈值补检
           → 灵敏度 95%→99%
           → 用搜索区间中点的分段阈值×0.5
```

### 噪声段掩码剔除（extract 中）

```
R 波检测后 → 识别噪声段（RMS<0.10 的 1 秒段）
           → 剔除落在噪声段内的 R 波（多为低振幅噪声误检）
           → 用 effectiveRPeaks 重算 RR/HRV/心率/间期/segments
```

**噪声段阈值 0.10mV 的依据**：
- 真实 wrist ECG 有效段（含 R 波）RMS 通常 >0.15mV（R 波振幅 0.5-1.5mV，1 秒内 1-2 个 R 波）
- 0.10mV 以下几乎不含有效 R 波信息
- 旧阈值 0.05mV 太严格：0.1mV 噪声段（RMS≈0.058）漏标 → 误检 R 波不剔除 → 虚假 RR

### HRV / 间期估测

- **HRV**：SDNN / RMSSD / pNN50，用 `filterEctopicBeats` 剔早搏后的 RR
- **心率**：avgHr/minHr/maxHr，从剔早搏 RR 计算
- **间期**：QRS/PR/QT/QTc(Bazett)/QTc(Fridericia)，本地估测，标注"本地估测"
- **节律判别**：RR 变异系数 / Poincaré 散点形态 / 短-长 RR 配对数

### toPromptText 输出格式

```
[用户属性]              ← 年龄性别（导出时未知）
[全局指标]              ← 采样率/R波数/心率/RR序列/SDNN/QRS/PR/QT/信号质量/噪声段
                        ← R波位置(样本索引):...  (新增，定位漏检/误检)
[节律判别特征]          ← RR变异系数/Poincaré/短-长配对
[逐秒分段]              ← 每秒一行：R波数/振幅范围/峰峰值/斜率/RMS
                        ← 逐秒RMS:...           (新增，紧凑曲线)
```

---

## 4. HeartVoice 教师信号方法论

### 为什么 HeartVoice 可作参考
HeartVoice API（`https://api.heartvoice.com.cn/api/v1/basic/ecg/1-lead/analyze`）是专业医疗 AI 服务，返回值来自服务端医疗级算法，经临床验证。单导联 ECG 无原生"心率值"，所有数值都来自算法分析波形——HeartVoice 的算法是当前可得的最佳参考。

**API 返回字段**：isAbnormal, sqGrade(信号质量), diagnosis[], possibleDiags[], isReverse, avgHr, avgQrs, avgP, prInterval, avgQt, avgQtc, pacCount, pvcCount, rawData(完整 JSON)

**API 不返回**：R 波位置、RR 间期序列、HRV(SDNN/RMSSD/pNN50)、Poincaré、minHr/maxHr（这两个由本地 `computeMinMaxHeartRate` 估算）

### 配对数据收集方法

1. 用 HeartVoice 路径测量 N 次（建议 3-4 次，覆盖不同心率/信号质量）
2. 每次导出诊断包 .txt
3. 导出文件含两段对照：
   - `[HeartVoice API 返回]`：avgHr/QRS/PR/diagnosis（教师信号）
   - `[算法提取后的结构化特征]`：本地算法结果（待优化对象）

### 差异定位流程

```
读导出文件
  │
  ├─ 对照 avgHr
  │   ├─ 一致（±5bpm）→ 本地 R 波检测在该样本上正常
  │   └─ 差异大 → 看 [算法提取后的结构化特征] 的 R波位置(样本索引)
  │       ├─ R 波数 < HeartVoice 暗示数 → 漏检
  │       │   └─ 看逐秒RMS，定位漏检段：是否噪声段误剔？阈值过高？
  │       └─ R 波数 > HeartVoice 暗示数 → 误检
  │           └─ 看噪声段标注，定位误检段：是否噪声段未剔除？
  │
  ├─ 对照 QRS/PR/QTc
  │   └─ 差异大 → 本地间期估测算法问题（形态学定位不准）
  │
  └─ 对照 diagnosis
      └─ 本地节律判别特征（RR变异系数/Poincaré）是否与 HeartVoice 诊断一致
```

---

## 5. 已知 Bug 与修复历史

### Bug 1：median×1.5 阈值压掉所有真实 R 波（致命，Build #123 修复）

**现象**：三个真实测量 HeartVoice 路径 avgHr 都有值，但本地算法 R 波检测 0 个 → 心率 0

**根因**：检测层曾加 `median×1.5` 下限防平坦段误检。但真实 wrist ECG 中 R 波包络峰仅为噪声中位数 ~1.4 倍（envMax≈106, median≈75），`median×1.5` 要求比值≥1.5 → 所有点低于阈值 → R 波检测 0 个

**证据**：三个真实 .txt 文件（samples/ECG_diagnostic_*.txt）所有段的 envMax/(median×1.5) 比值都 <1.0（0.82~0.98）

**修复**：去掉 median×1.5，回到 mean+2std；平坦段防护改由噪声段掩码剔除兜底，不在检测层用 median 约束

**验证**：Python 模拟确认 0→26/21/26 R 波，HR 0→52/41/53 bpm

**commit**：`98aa05b`

### Bug 2：噪声段阈值 0.05 太低（Build #123 一并修复）

**根因**：0.1mV 噪声段 RMS≈0.058 > 0.05 → 漏标 → 误检 R 波不剔除 → 虚假 RR

**修复**：阈值 0.05→0.10mV

### Bug 3：DiagnosticExporter 对 HeartVoice 路径重建本地特征 → 导出全 0（Build #126 修复）

**现象**：HeartVoice 测量 UI 显示正常（API 返回了有效数据），但导出的 .txt 文件 `[全局指标]` 全 0

**根因**：`buildText()` 不管 analysisMethod 都调 `EcgFeatureExtractor.extract()` 重建特征；HeartVoice 路径本地算法在真实信号上返回 0 → 导出全 0，但 API 实际返回了有效数据

**修复**：HeartVoice 路径加 `[HeartVoice API 返回]` 段，输出 transfer 已有的 avgHeartRate/diagnosis/avgQrs/prInterval 等

**commit**：`56e0a5a`

### Bug 4：MWI 窗口 50ms 太窄导致双峰（早期修复）

**根因**：旧实现用 50ms boxcar，一个 QRS 的 R 波+R' 切迹会产生双峰 → 双 R 峰检测 → 虚假短 RR → 心率虚高 + 误判早搏/房颤

**修复**：50ms → 150ms（Pan-Tompkins 原版标准）

### Bug 5：精修后 lastPeakIdx 未更新（早期修复）

**根因**：不应期基于包络峰位置计算，与实际 R 峰位置偏差累积，可能让相邻 QRS 的精修位置进入同一不应期窗口

**修复**：lastPeakIdx 必须用精修后的 bestIdx

### Bug 6：运动后 R 波漏检 60% + 静息过度检出（v3 百分位 0.92 方案两难）

**现象**：
- 运动后 wrist ECG（samples/运动后.txt，HV 107bpm）：mean+2.0std 检出仅 21 个 R 波（期望 53），本地心率 54bpm
- v3 改百分位 0.92 + 形态验证后：合成信号通过，但真实静息信号过度检出（48 R 波 vs 期望 27）

**根因（双重）**：
1. 运动后漏检：肌电干扰让 envelope 右偏长尾（少量极高值），mean+2std 被长尾拉高 → 真实 R 波 envelope 峰仅 0.85~1.11 倍阈值 → 漏检
2. v3 静息过度检出：所有真实信号上 pct0.92 都比 mean+2std 低 2-3 单位（debug_real_threshold.py），min 总取 pct0.92（过低）→ 静息噪声通过
3. v3 形态验证误杀：envelope 峰在 R 峰前 40-50ms 上升沿（梯度最大处），候选点 |hp| 低，右侧 50ms 处正好在 R 峰 |hp| 高 → 误杀真实 R 波（339 个失败）

**修复（v4 mean+1.7std）**：
- k 从 2.0 降到 1.7（不取 min，直接降 k），让运动后边缘 R 波通过
- 删除形态验证（主检测 + 回溯补检），用噪声段掩码 + filterEctopicBeats 兜底
- k=1.7 选择依据：k=1.8 有 3/6 seed <33（漏检），k=1.6 真实样本短 RR 偏多，1.7 平衡

**验证（verify_k18.py，k=1.7）**：
- 合成 6 seed 全部 >=34（s200=37, s201=42, s202=35, s300=37, s400=34, s500=39）
- 5 份真实样本：静息/活动后全部准确（HR 偏差 <15bpm），运动后 21→36 R 波改善

**k=1.7 是经验调参值（非临床金标准）**，依据是 5 份真实 wrist ECG + 6 seed 合成信号

### Bug 7：精修窗口 ±25ms 够不到 R 峰 → k<2.0 时 HR range>10（Build #29502533615 修复）

**现象**：commit a834d69（k=1.7）推送后 CI 失败，`detectsRegularRPeaksAndHeartRate` 在 line 93
失败（`maxHr - minHr <= 10` 断言）。

**根因（用 JavaRandom 精确复现 Kotlin 测试，test_kotlin_exact.py）**：
- k=2.0：HR range=9 PASS；k=1.9/1.8/1.7：HR range=11 FAIL
- 所有 k 值都检测到 35 个 R 波（数量正确），但 k<2.0 时 R 峰位置漂移导致 RR 范围扩大

**深入定位（debug_rpos.py）**：
- envelope（|梯度| 150ms 平滑）的峰位于 R 波上升沿最陡处，比真正的 R 峰**提前 ~30-50ms**
  （合成 Gaussian σ=10ms 信号实测：envelope 峰在 R 峰前 29 samples=58ms）
- 旧实现精修窗口 ±25ms (`sampleRateHz/40`) **够不到真正的 R 峰**
- ±25ms 找到的是上升沿上的噪声局部最大值 → R 峰位置漂移 ±6 samples (12ms)
  → RR 范围从 110ms 扩大到 126ms → HR range 从 9 变为 11 > 10
- k=2.0 时阈值高，候选点位置稳定，恰好命中"上升沿+1样本"位置，HR range=9；
  k<2.0 时阈值低，候选点位置变化，精修落在不同上升沿噪声点，HR range=11

**修复（v5 ±50ms 精修窗口）**：
- 精修窗口 ±25ms → ±50ms（`sampleRateHz/40` → `sampleRateHz/20`）
- 主检测和回溯补检两处精修都改
- 依据：Pan-Tompkins 1985 原版用 ±50ms 精修窗口；150ms MWI 让 envelope 峰前移是已知特性

**验证（test_refine_window.py + test_refine_all.py，JavaRandom 精确复现）**：
- detectsRegularRPeaksAndHeartRate：R=35, HR range=2（从 11 降到 2）✅
- detectsRPeaksInMotionArtifactSignal：6 seed 全部 >=34，min=34 ✅
- 其他依赖 R 峰的测试（clean+noise / moderate noise / noise mask / segment / flat segment）均无回归
- 测试3 flatSegmentWithMicroNoiseDoesNotProduceFalsePeaks：Python 模拟 ±25ms 和 ±50ms 都给 32
  （Python random 与 Java random 差异），但 Kotlin/CI 中此测试本来就通过——
  CI 失败报告只显示 detectsRegularRPeaksAndHeartRate 失败，证明 flatSegment 在 Kotlin 中已通过

**未破坏的测试**（test_refine_all.py 验证）：
| 测试 | ±25ms | ±50ms |
|------|-------|-------|
| detectsRegularRPeaksAndHeartRate | ❌ range=11 | ✅ range=2 |
| detectsRPeaksInMotionArtifactSignal | ✅ min=34 | ✅ min=34 |
| detectsRPeaksInCleanSegmentDespiteNoise | ✅ R=32 | ✅ R=34 |
| detectsRPeaksInModerateNoiseRealisticSignal | ✅ R=35 | ✅ R=35 |
| noiseSegmentRPeaksDoNotPolluteStats | ✅ RR=22 | ✅ RR=22 |
| segmentRPeakCountMatchesInput | ✅ ones=30 | ✅ ones=30 |

---

## 6. 关键约束与规范（继承约束，必须遵守）

### 阈值要有依据
- 一切阈值要有文献/数据/工程经验依据，不拍脑袋设值
- 工程经验值（非临床金标准）如实标注
- 例：噪声段 0.10mV 依据是"真实 wrist ECG 有效段 RMS 通常 >0.15mV"
- 例：4秒分段窗口依据是"覆盖约 4-5 个心动周期，统计稳健"

### TDD 流程
1. 先写失败测试（RED），验证失败
2. 再写最小实现（GREEN），验证通过
3. 必要时重构（REFACTOR）

测试位置：
- [shared/src/test/java/com/wearhealth/companion/shared/EcgFeatureExtractorTest.kt](../shared/src/test/java/com/wearhealth/companion/shared/EcgFeatureExtractorTest.kt)
- [shared/src/test/java/com/wearhealth/companion/shared/DiagnosticExporterTest.kt](../shared/src/test/java/com/wearhealth/companion/shared/DiagnosticExporterTest.kt)

### 不本地构建
- 不进行本地 Gradle 构建（沙箱无 Android SDK）
- 只允许 `git diff --check`（语法检查）和 `python3 -m unittest`（Python 模拟验证测试逻辑）
- 实际测试由 CI（GitHub Actions）运行

### Git/CI 约束
- 标准 remote 必须保持为 `https://github.com/hongquangphung7044-oss/WearHealthCompanion.git`
- PAT 绝对不能写入任何持久化位置（config/credentials/脚本），只能临时内嵌 URL 使用
- README 顶部 `AUTO_BUILD_STATUS` 区块不能手工修改（由 `scripts/update_readme_build_status.py` 自动维护）
- 不回退 Build #59 的 BLE MTU 修复、ACK 幂等语义、ECG 采集约束

### DS 约束
- 只用 V4-Flash 两档（均衡=high 思考强度，Max=max 思考强度）
- 不用 Pro

---

## 7. 待优化方向

### 7.1 本地 R 波检测在真实数据上的稳定性
- **状态**：合成 ECG 测试通过；median×1.5 bug 已修；运动后漏检 60% 已修（v4 mean+1.7std）；精修窗口 ±25ms→±50ms 修复 R 峰位置漂移（v5，Bug 7）；精修二次不应期 300ms 消除超短 RR（v6/v7，Bug 8-9）；回溯补检 T 波排除消除 T 波误补检（v6/v7，Bug 8-9）；R 峰可靠性评估三档降级防止 R 峰不可信时误判房颤（v6，Bug 8）
- **方法**：收集 3-4 次真实 HeartVoice 路径导出文件，对照 avgHr 差异
- **风险**：真实 wrist ECG 噪声更复杂（基线漂移+运动伪差+电极接触不良），可能有未发现的退化模式
- **当前验证**：7 份真实样本（静息/活动后/运动后/223211/专业/ds均衡/dsmax）HR 偏差均 <15bpm 或改善；v7 后 6/7 样本可靠性达"可信"，1 个"边缘"（专业样本 R 峰欠检 25 vs 期望 30，300ms 不应期合并了部分真 R 波的 envelope 双峰）

### 7.2 间期估测（QRS/PR/QT）准确性
- **状态**：本地估测，未与 HeartVoice 系统对照
- **方法**：用导出文件的 `[HeartVoice API 返回]` QRS/PR/QTc 对照本地 `[算法提取后的结构化特征]` 的同名字段
- **风险**：本地形态学定位（R 峰/S 波/T 波终点）在噪声段可能不准

### 7.3 节律判别（房颤/早搏/窦性）
- **状态**：基于 RR 变异系数/Poincaré/短-长配对，未系统验证
- **方法**：对照 HeartVoice diagnosis（SN/AF/VPB/APB 等）
- **风险**：wrist ECG 噪声易产生虚假短-长 RR，误判早搏

#### 心律疾病泛化策略（关键设计决策）

R 波检测层是**节律无关的（rhythm-agnostic）公共上游**——只要 QRS 形态正常，
无论窦性/房颤/早搏/室上速，R 波都能被检出。节律判别完全交给 DS，依据本地
提供的 RR 统计特征推理。

HeartVoice 5 份样本全是窦性（SN/SNB），**不能用来反推 AF/AFL 参数**——
用窦性样本调出的阈值套到房颤上会过拟合。因此：
- 本地只做"测量"（R 波位置、RR 序列、变异系数、Poincaré 形态、短-长配对数）
- 节律"判读"归 DS（看 RR 不规律度 + Poincaré 形态 + 临床语境综合判断）

#### 三个基本心律的参数特征（本地测量 → DS 判读依据）

| 心律 | RR 规律性 | 变异系数 (SDNN/meanRR) | Poincaré 形态 | 短-长配对 |
|------|----------|----------------------|---------------|----------|
| 窦性 (SN) | 规律（相邻 RR 差 <10%） | <0.05 | 彗星形（compact comet） | 0 |
| 房颤 (AF) | 极不规律（相邻 RR 差 >20%） | >0.15 | 扇形（fan-shaped，散点弥漫） | 0（无代偿间隙） |
| 早搏 (PAC/PVC) | 多数规律 + 突发短 RR | 中等 | 鱼雷形 + 离群点 | >=1（短-长代偿间隙） |

**判读逻辑（DS 推理，非本地硬编码）**：
- 变异系数 >0.15 + Poincaré 扇形 → 提示房颤（AF）
- shortLongPairs >=1 + Poincaré 鱼雷形/离群点 → 提示早搏（PAC/PVC）
- 变异系数 <0.05 + Poincaré 彗星形 → 提示窦性（SN）

#### "风险低的心律"说明

用户问"那几个风险低的心律是啥"——指 HeartVoice 诊断里**风险分级较低**的常见心律，
通常包括：
1. **窦性心律 (SN) / 窦性心律不齐 (SNB)**：正常心律，青少年 SNB 常为生理性
2. **窦性心动过缓 (SB)**：心率 <60bpm，运动员/睡眠时常见，多为生理性
3. **窦性心动过速 (ST)**：心率 >100bpm，运动/紧张/发热引起，去除诱因即恢复

这三类都是窦性起源的"正常变异"，相对房颤/室速等高风险心律，临床风险低。
HeartVoice 5 份样本的诊断（SN/SNB）正属于此类。

**本地算法对低风险心律的处理**：R 波检测层节律无关，窦性/窦不齐/窦缓/窦速
都能正确检出 R 波 → 正确计算 RR/HRV/间期 → DS 拿到准确特征 → 给出正确判读。
不需要为"低风险心律"单独调参。

### 7.4 信号质量指数
- **状态**：`computeSignalQuality` 基于噪声段比例+心率合理性+RMS
- **方法**：对照 HeartVoice sqGrade
- **风险**：本地指数可能与 HeartVoice 不一致，影响"何时信任结果"的判断

---

## 8. 文件索引

### 核心算法
- [shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt) — 本地特征提取（R 波检测/HRV/间期/toPromptText/R 峰可靠性评估），**算法核心，v7 版本**
- [app/src/main/java/com/wearhealth/companion/model/EcgAnalysisResult.kt](../app/src/main/java/com/wearhealth/companion/model/EcgAnalysisResult.kt) — 数据模型 + `computeMinMaxHeartRate`（独立本地算法）
- [app/src/main/java/com/wearhealth/companion/network/HeartVoiceApiClient.kt](../app/src/main/java/com/wearhealth/companion/network/HeartVoiceApiClient.kt) — HeartVoice API 客户端

### 导出/对照
- [shared/src/main/java/com/wearhealth/companion/shared/DiagnosticExporter.kt](../shared/src/main/java/com/wearhealth/companion/shared/DiagnosticExporter.kt) — 诊断包导出（三段对照）
- [shared/src/main/java/com/wearhealth/companion/shared/EcgMeasurementTransfer.kt](../shared/src/main/java/com/wearhealth/companion/shared/EcgMeasurementTransfer.kt) — 测量数据传输模型

### 测试（CI 实际运行）
- [shared/src/test/java/com/wearhealth/companion/shared/EcgFeatureExtractorTest.kt](../shared/src/test/java/com/wearhealth/companion/shared/EcgFeatureExtractorTest.kt) — 算法回归测试（含 `syntheticEcg` 辅助函数，24 个 @Test）
- [shared/src/test/java/com/wearhealth/companion/shared/DiagnosticExporterTest.kt](../shared/src/test/java/com/wearhealth/companion/shared/DiagnosticExporterTest.kt) — 导出功能测试

### 辅助验证脚本（Python，本地沙箱用，不进 CI）
> **重要**：Python `random.Random` ≠ Java `java.util.Random`，即使 seed 相同也产生不同序列。
> 对 Kotlin 测试场景的精确预测**必须用 `java_random.py` 的 `JavaRandom` 类**。

- [java_random.py](../java_random.py) — Java `java.util.Random` 的精确 Python 复现（LCG + nextDouble + nextGaussian）
- [test_kotlin_exact.py](../test_kotlin_exact.py) — 用 JavaRandom 精确复现 Kotlin 测试，验证不同 k 值的 HR range
- [test_refine_window.py](../test_refine_window.py) — 测试不同精修窗口宽度对 HR range 的影响（Bug 7 修复依据）
- [test_refine_all.py](../test_refine_all.py) — ±50ms 精修窗口对所有依赖 R 峰的测试场景的影响（回归验证）
- [verify_k18.py](../verify_k18.py) — k=1.7 全面验证（6 seed 运动 + 5 真实样本，用 Python random，仅参考）
- [test_backtrack.py](../test_backtrack.py) — k=2.0 + 可调回溯参数测试（证明 k=2.0 无法救场运动后漏检）
- [debug_rpos.py](../debug_rpos.py) — 诊断 R 峰位置漂移根因（Bug 7 定位工具）

### 真实数据样本（HeartVoice 路径导出诊断包）
- [samples/运动后.txt](../samples/运动后.txt) — 运动后 wrist ECG（HV 107bpm SNT），**最关键的漏检样本**
- [samples/静息.txt](../samples/静息.txt) — 静息 wrist ECG（HV 54bpm SNB）
- [samples/心电api静息换右手.txt](../samples/心电api静息换右手.txt) — 换右手静息（HV 59bpm SNB）
- [samples/心电api活动后.txt](../samples/心电api活动后.txt) — 活动后（HV 72bpm SN）
- [samples/心电api静息.txt](../samples/心电api静息.txt) — 静息（HV 59bpm SNB）
- [samples/ECG_diagnostic_20260716_182620.txt](../samples/ECG_diagnostic_20260716_182620.txt) — 早期真实诊断包（15410 点）
- [samples/ECG_diagnostic_20260716_182859.txt](../samples/ECG_diagnostic_20260716_182859.txt) — 早期真实诊断包（15320 点）
- [samples/ECG_diagnostic_20260716_183119.txt](../samples/ECG_diagnostic_20260716_183119.txt) — 早期真实诊断包（15320 点）

### CI/构建
- [.github/workflows/build-apk.yml](../.github/workflows/build-apk.yml) — GitHub Actions 构建配置
- [scripts/update_readme_build_status.py](../scripts/update_readme_build_status.py) — README 构建状态自动更新

---

## 9. 上手清单（给接手 AI/开发者）

1. 读本文档第 2-3 节，理解数据流和算法架构
2. 读 [EcgFeatureExtractor.kt](../shared/src/main/java/com/wearhealth/companion/shared/EcgFeatureExtractor.kt) 的 `detectRPeaks` 和 `extract`，对照本文档第 3 节
3. 读 [DiagnosticExporter.kt](../shared/src/main/java/com/wearhealth/companion/shared/DiagnosticExporter.kt)，理解导出文件结构
4. 看 [samples/](../samples/) 下的真实诊断包，理解真实 wrist ECG 的噪声特征
5. 读本文档第 4 节，理解 HeartVoice 教师信号方法论
6. 读本文档第 6 节，牢记约束规范
7. 开始优化：按第 7 节的待优化方向，TDD 流程推进

**优化循环**：
```
收集真实导出样本 → 对照 HeartVoice vs 本地算法 → 定位差异 → 假设根因 →
写失败测试（RED）→ 写最小实现（GREEN）→ Python 模拟验证 → git diff --check →
提交推送 → 等 CI → 确认通过 → 下一个差异点
```
