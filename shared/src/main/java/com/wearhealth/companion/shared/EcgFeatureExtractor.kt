package com.wearhealth.companion.shared

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ECG 特征提取器：从原始波形数据提取统计特征，用于喂给 DeepSeek 做医学推理
 *
 * 设计原则：
 * 1. 本地完成所有 DSP 工作（R 波检测、HRV、间期估测），不指望 LLM 算数字
 * 2. 输出结构化文本（全局指标 + 逐秒分段表），token 量可控（约 1500~2500）
 * 3. 间期估测标注误差范围（±15ms），让 DS 知道这是估测值而非精确测量
 * 4. 噪声段明确标记，避免 DS 误判
 *
 * 数据来源：mV×1000 整数，500Hz，30 秒 ≈ 15000 点
 */
object EcgFeatureExtractor {

    /** 用户基本属性（影响 QTc/HRV 判断阈值） */
    data class UserProfile(
        val ageYears: Int = 0,        // 0=未知
        val isMale: Boolean? = null,  // null=未知
    )

    /** 全局特征（整段测量） */
    data class GlobalFeatures(
        val sampleRateHz: Int,
        val durationSec: Float,
        val totalSamples: Int,
        val rPeakCount: Int,
        val avgHeartRate: Int,          // bpm
        val minHeartRate: Int,
        val maxHeartRate: Int,
        val rrIntervalsMs: List<Int>,   // R-R 间期序列
        val sdnnMs: Float,              // RR 标准差
        val rmssdMs: Float,             // 相邻 RR 差均方根
        val pnn50Pct: Float,            // 相邻 RR 差>50ms 占比
        val qrsWidthMs: Int,            // 本地估测
        val qrsWidthStdMs: Int,
        val prIntervalMs: Int,          // 本地估测
        val prIntervalStdMs: Int,
        val qtIntervalMs: Int,          // 本地估测
        val qtcMs: Int,                 // Bazett 校正（静止测量 60bpm 附近最准）
        val qtcFridericiaMs: Int = 0,   // Fridericia 校正（心率波动大时更稳，50-90bpm 全区间误差小）
        val rAmplitudeMv: Float,        // R 波平均振幅
        val signalQuality: Float,       // 0~1
        val noiseSegments: List<String>,// 噪声段时段标记
        // 节律判别辅助特征（帮助 DS 区分窦性/房颤/早搏）
        val rrVariabilityCoef: Float,   // RR 变异系数 = SDNN/meanRR，>0.15 提示心律不齐/房颤
        val poincarePattern: String,    // Poincaré 散点形态描述（彗星形/扇形/鱼雷形/复杂形）
        val shortLongPairs: Int,        // 短-长 RR 配对数（早搏代偿间隙特征）
        val ppgReferenceHr: Int = 0,    // PPG 绿光参考心率（测后读系统心率，0=未采集/不可用）
        val rPeakIndices: List<Int> = emptyList(),  // R 波位置索引（样本索引，噪声段剔除后的有效 R 波）
    )

    /** 逐秒分段特征 */
    data class SegmentFeature(
        val startSec: Float,
        val endSec: Float,
        val rPeakCount: Int,
        val ampMinMv: Float,
        val ampMaxMv: Float,
        val peakToPeakMv: Float,
        val maxSlopeMvPerSec: Float,
        val rmsMv: Float,
    )

    /** 完整提取结果 */
    data class FeatureBundle(
        val global: GlobalFeatures,
        val segments: List<SegmentFeature>,
        val profile: UserProfile,
    )

    /**
     * 主入口：从原始 ECG 数据提取全部特征
     *
     * @param ecgData ECG 数据（mV×1000 整数）
     * @param sampleRateHz 采样率（默认 500）
     * @param profile 用户属性（年龄性别，可选）
     */
    fun extract(
        ecgData: List<Int>,
        sampleRateHz: Int = 500,
        profile: UserProfile = UserProfile(),
        ppgReferenceHr: Int = 0,
    ): FeatureBundle {
        val durationSec = if (sampleRateHz > 0) ecgData.size.toFloat() / sampleRateHz else 0f
        val rPeaks = detectRPeaks(ecgData, sampleRateHz)

        // 噪声段掩码剔除：先识别噪声段（rms<0.10 的 1 秒段），剔除落在噪声段内的 R 波。
        // 这些 R 波多为低振幅噪声偶尔触发的误检，不应进入 HRV/心率/间期/segments 统计。
        // 跨噪声段的 RR（前段尾→后段头）也会因 R 波剔除而不产生，避免虚假长/短 RR 污染节律判别。
        // 依据：视图模型建议第 2 条"噪声段强制剔除不参与 HRV/节律运算"；
        //       Lueken 2023 (Sensors) 证实信号质量差的段显著增加房颤误分类概率
        // 噪声段阈值 0.10mV：真实 wrist ECG 有效段（含 R 波）RMS 通常 >0.15mV
        // （R 波振幅 0.5-1.5mV，1 秒内 1-2 个 R 波）；0.10mV 以下几乎不含有效 R 波信息。
        // 旧阈值 0.05mV 太严格，0.1mV 噪声段（RMS≈0.058）漏标 → 误检 R 波不剔除 → 虚假 RR。
        val segmentsRaw = extractSegments(ecgData, sampleRateHz, rPeaks, durationSec)
        val noiseSampleRanges = segmentsRaw.filter { it.rmsMv < 0.10f }
            .map { (it.startSec * sampleRateHz).toInt() until (it.endSec * sampleRateHz).toInt() }
        val effectiveRPeaks = if (noiseSampleRanges.isEmpty()) rPeaks
            else rPeaks.filter { peak -> noiseSampleRanges.none { range -> peak in range } }

        val rrIntervals = computeRRIntervals(effectiveRPeaks, sampleRateHz)
        // RR 双通路：HRV 和心率统计用剔早搏 RR，节律判别保原始 RR
        // 早搏短长 RR 会同时污染 HRV 和心率范围（短 RR→虚高 maxHr，长 RR→虚低 minHr）
        val rrForHrv = filterEctopicBeats(rrIntervals)
        val hr = computeHeartRateStats(rrForHrv, profile.ageYears)
        val hrv = computeHrv(rrForHrv)
        val rhythm = computeRhythmFeatures(rrIntervals)
        val intervals = estimateIntervals(ecgData, sampleRateHz, effectiveRPeaks, hr.avgHr)
        val rAmp = computeRAmplitude(ecgData, effectiveRPeaks, sampleRateHz)
        // 重算 segments 用 effectiveRPeaks，让噪声段 rPeakCount=0
        val segments = extractSegments(ecgData, sampleRateHz, effectiveRPeaks, durationSec)
        val noiseSegs = segments.filter { it.rmsMv < 0.10f }
            .map { "${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s(噪声)" }
        val sigQuality = computeSignalQuality(segments, effectiveRPeaks.size, durationSec)

        val global = GlobalFeatures(
            sampleRateHz = sampleRateHz,
            durationSec = durationSec,
            totalSamples = ecgData.size,
            rPeakCount = effectiveRPeaks.size,
            avgHeartRate = hr.avgHr,
            minHeartRate = hr.minHr,
            maxHeartRate = hr.maxHr,
            rrIntervalsMs = rrIntervals,
            sdnnMs = hrv.sdnn,
            rmssdMs = hrv.rmssd,
            pnn50Pct = hrv.pnn50,
            qrsWidthMs = intervals.qrsMean,
            qrsWidthStdMs = intervals.qrsStd,
            prIntervalMs = intervals.prMean,
            prIntervalStdMs = intervals.prStd,
            qtIntervalMs = intervals.qtMean,
            qtcMs = intervals.qtc,
            qtcFridericiaMs = intervals.qtcFridericiaMs,
            rAmplitudeMv = rAmp,
            signalQuality = sigQuality,
            noiseSegments = noiseSegs,
            rrVariabilityCoef = rhythm.variabilityCoef,
            poincarePattern = rhythm.poincarePattern,
            shortLongPairs = rhythm.shortLongPairs,
            ppgReferenceHr = ppgReferenceHr,
            rPeakIndices = effectiveRPeaks,
        )
        return FeatureBundle(global, segments, profile)
    }

    // ===== 内部算法 =====

    /**
     * R 波检测（NeuroKit 风格预处理 + Pan-Tompkins 回溯补检）
     *
     * 预处理链（借鉴 NeuroKit2 'neurokit' 方法）：
     * 1. 1秒窗口去基线漂移（等效高通 ~1Hz）
     * 2. |梯度|（一阶中心差分绝对值）—— 突出 QRS 陡峭斜率，抑制平缓 P/T 波
     * 3. 50ms boxcar 平滑 —— 形成包络，减少噪声毛刺（不用 750ms 因 500Hz 下太宽会吞掉相邻 R 波）
     *
     * 检测（借鉴 Pan-Tompkins）：
     * 4. 自适应阈值 = 均值 + 2×标准差
     * 5. 局部最大 + 332ms 不应期（最高 180bpm）
     * 6. 原始信号 ±24ms 精修 R 峰位置
     * 7. 回溯补检：RR > 1.66×近期均值时半阈值补检（PT 核心，灵敏度 95%→99%）
     */
    private fun detectRPeaks(ecgData: List<Int>, sampleRateHz: Int): List<Int> {
        if (ecgData.size < sampleRateHz * 5) return emptyList()

        // 1. 去基线漂移（1秒窗口移动平均）
        val baselineWindow = sampleRateHz
        val baseline = DoubleArray(ecgData.size)
        for (i in ecgData.indices) {
            val from = maxOf(0, i - baselineWindow / 2)
            val to = minOf(ecgData.size, i + baselineWindow / 2)
            var sum = 0.0
            for (j in from until to) sum += ecgData[j]
            baseline[i] = sum / (to - from)
        }
        val highPassed = DoubleArray(ecgData.size) { i -> ecgData[i] - baseline[i] }

        // 2. |梯度|（一阶中心差分绝对值，突出 QRS 斜率）
        val gradient = DoubleArray(highPassed.size)
        for (i in highPassed.indices) {
            val prev = highPassed[maxOf(0, i - 1)]
            val next = highPassed[minOf(highPassed.lastIndex, i + 1)]
            gradient[i] = abs(next - prev) / 2.0
        }

        // 3. 150ms boxcar 平滑（MWI 移动窗口积分，Pan-Tompkins 原版标准）
        // 窗口宽度必须覆盖整个 QRS 复合波（正常 100ms，病理可达 150ms），让一个 QRS
        // 在包络上只产生一个峰。旧实现用 50ms 太窄，一个 QRS 的 R 波+R'切迹会产生双峰，
        // 导致双 R 峰检测 → 虚假短 RR → 心率虚高 + 误判早搏/房颤
        // 依据：Pan-Tompkins 1985 原版；Pan-Tompkins++ (Imtiaz 2022) 同样用 150ms
        val smoothWin = (sampleRateHz * 0.150).toInt().coerceAtLeast(3)  // 150ms
        val envelope = DoubleArray(gradient.size)
        for (i in gradient.indices) {
            val from = maxOf(0, i - smoothWin / 2)
            val to = minOf(gradient.size, i + smoothWin / 2 + 1)
            var sum = 0.0
            for (j in from until to) sum += gradient[j]
            envelope[i] = sum / (to - from)
        }

        // 4. 分段自适应阈值（解决局部噪声污染全局阈值）
        // 旧实现用全局 envMean+2×envStd，真实信号中某段有大运动伪差时 envStd 被拉高，
        // 导致干净段的 R 波梯度全部低于阈值 → 漏检 → 心率虚低（如 33bpm）+ 虚假长 RR。
        // 分段阈值：按 4 秒窗口分段计算各自 mean+2×std，逐点用所属段阈值判定。
        // 4 秒覆盖约 4-5 个心动周期，统计稳健；窗口太短（如 1 秒）样本不足 std 不稳。
        // 依据：Pan-Tompkins 原版用自适应阈值但带短时记忆；分段独立阈值是工程改进，
        // 避免单一噪声段污染整段测量（非临床金标准，针对 wrist-wear 噪声场景）
        //
        // 历史教训：曾尝试加 median×1.5 下限防平坦段误检，但真实 wrist ECG 数据
        // （samples/ECG_diagnostic_*.txt）证实 R 波包络峰仅为噪声中位数 ~1.4 倍，
        // median×1.5 把所有真实 R 波压掉 → R 波检测 0 个 → 心率 0（彻底失效）。
        // 平坦段误检改由噪声段掩码剔除（extract() 中 RMS<0.1 的段剔除）兜底，
        // 不在检测层用 median 约束压阈值。
        val segLen = sampleRateHz * 4  // 4 秒一段
        val thresholds = DoubleArray(envelope.size)
        for (segStart in 0 until envelope.size step segLen) {
            val segEnd = minOf(envelope.size, segStart + segLen)
            val segSize = segEnd - segStart
            if (segSize <= 0) continue
            var sum = 0.0
            for (i in segStart until segEnd) sum += envelope[i]
            val segMean = sum / segSize
            var varSum = 0.0
            for (i in segStart until segEnd) {
                val d = envelope[i] - segMean
                varSum += d * d
            }
            val segStd = sqrt(varSum / segSize)
            val segThreshold = segMean + segStd * 2.0
            for (i in segStart until segEnd) thresholds[i] = segThreshold
        }
        // 全局最低阈值保护：纯静音/全零段阈值可能极低，加 floor 避免噪声误判
        val globalFloor = 3.0

        // 5. R 峰检测：阈值 + 局部最大 + 不应期
        // 不应期 200ms（Pan-Tompkins 原版 MINPEAKDISTANCE=200ms，生理上 RR 不可能 <200ms）
        // 旧实现 332ms 偏保守但可接受；真正 bug 在精修后 lastPeakIdx 未更新为 bestIdx
        val rPeaks = mutableListOf<Int>()
        val refractory = sampleRateHz / 5  // 200ms 不应期（500/5=100样本，最高 300bpm 上限）
        var lastPeakIdx = -refractory * 2
        val checkRange = sampleRateHz / 50  // ±10ms 局部最大检查（500/50=10样本=±10ms）

        for (i in envelope.indices) {
            val thr = maxOf(thresholds[i], globalFloor)
            if (envelope[i] < thr) continue
            val lo = maxOf(0, i - checkRange)
            val hi = minOf(envelope.size, i + checkRange + 1)
            var isLocalMax = true
            for (j in lo until hi) {
                // 用 >= 防 plateau 双峰：两相邻点包络值相等时，只认第一个，避免双峰
                if (j != i && envelope[j] >= envelope[i]) {
                    if (j < i) { isLocalMax = false; break }  // 前面有等值或更高峰，当前不是第一个
                }
            }
            if (!isLocalMax) continue
            if ((i - lastPeakIdx) < refractory) continue

            // 6. 精修：在原始去基线信号 ±25ms 邻域找真正的 R 峰（梯度峰可能偏移几ms）
            val refineLo = maxOf(0, i - sampleRateHz / 40)
            val refineHi = minOf(highPassed.size, i + sampleRateHz / 40)
            var bestIdx = i
            var bestVal = 0.0
            for (j in refineLo until refineHi) {
                if (abs(highPassed[j]) > bestVal) {
                    bestVal = abs(highPassed[j])
                    bestIdx = j
                }
            }
            rPeaks.add(bestIdx)
            // 关键修复：lastPeakIdx 必须用精修后的 bestIdx，否则不应期基于包络峰位置计算，
            // 与实际 R 峰位置偏差累积，可能让相邻 QRS 的精修位置进入同一不应期窗口
            lastPeakIdx = bestIdx
        }

        // 7. 回溯补检（Pan-Tompkins 核心）：RR > 1.66×近期均值时可能有漏检
        if (rPeaks.size >= 5) {
            val extraPeaks = mutableListOf<Int>()
            for (k in 1 until rPeaks.size) {
                val rr = rPeaks[k] - rPeaks[k - 1]
                // 计算近期 RR 均值（前后各 4 个），排除当前 k 索引的 RR
                // 标准 Pan-Tompkins 用排除当前间期的邻居均值，避免长 RR 自身污染均值
                val recentStart = maxOf(0, k - 4)
                val recentEnd = minOf(rPeaks.size, k + 4)
                val recentRRs = mutableListOf<Int>()
                for (m in recentStart + 1 until recentEnd) {
                    if (m == k) continue  // 排除当前待判定的长 RR
                    recentRRs.add(rPeaks[m] - rPeaks[m - 1])
                }
                if (recentRRs.size < 3) continue
                val rrAvg = recentRRs.average()
                if (rrAvg < 1.0) continue

                if (rr > rrAvg * 1.66) {
                    // 半阈值搜索漏检区间：用搜索区间中点的分段阈值（漏检段阈值）
                    val midIdx = (rPeaks[k - 1] + rPeaks[k]) / 2
                    val midThr = if (midIdx in thresholds.indices) thresholds[midIdx] else globalFloor
                    val backThr = maxOf(midThr, globalFloor) * 0.5
                    val searchStart = rPeaks[k - 1] + refractory
                    val searchEnd = rPeaks[k] - refractory
                    if (searchEnd <= searchStart) continue
                    var bestBackIdx = -1
                    var bestBackVal = backThr
                    for (j in searchStart..searchEnd) {
                        if (j < 0 || j >= envelope.size) continue
                        if (envelope[j] > bestBackVal) {
                            val bLo = maxOf(0, j - checkRange)
                            val bHi = minOf(envelope.size, j + checkRange + 1)
                            var isMax = true
                            for (m in bLo until bHi) {
                                if (m != j && envelope[m] > envelope[j]) { isMax = false; break }
                            }
                            if (isMax) {
                                bestBackVal = envelope[j]
                                bestBackIdx = j
                            }
                        }
                    }
                    if (bestBackIdx >= 0) {
                        // 精修
                        val rLo = maxOf(0, bestBackIdx - sampleRateHz / 40)
                        val rHi = minOf(highPassed.size, bestBackIdx + sampleRateHz / 40)
                        var rIdx = bestBackIdx
                        var rVal = 0.0
                        for (j in rLo until rHi) {
                            if (abs(highPassed[j]) > rVal) {
                                rVal = abs(highPassed[j])
                                rIdx = j
                            }
                        }
                        extraPeaks.add(rIdx)
                    }
                }
            }
            if (extraPeaks.isNotEmpty()) {
                rPeaks.addAll(extraPeaks)
                rPeaks.sort()
            }
        }

        return rPeaks
    }

    /**
     * RR 间期计算 + 生理学范围过滤
     *
     * 范围 300-2500ms（24-200bpm）的生理学依据：
     * - 下限 300ms = 200bpm：极端心动过速上限，室上速 SVT 可达 150-250bpm（生理学教材）
     *   早搏联律间期最短约 300ms，再短多为噪声/误检
     * - 上限 2500ms = 24bpm：极端心动过缓（病理但存在），代偿间隙可至 2000ms+
     * - 窦性心律正常 600-1000ms（60-100bpm），本宽范围保留早搏短长 RR 用于节律判别
     *
     * 注意：HRV/心率统计另有 filterEctopicBeats（±20%, Karey 2019）剔早搏
     */
    private fun computeRRIntervals(rPeaks: List<Int>, sampleRateHz: Int): List<Int> {
        if (rPeaks.size < 2) return emptyList()
        val rr = mutableListOf<Int>()
        for (i in 1 until rPeaks.size) {
            val rrMs = ((rPeaks[i] - rPeaks[i - 1]) * 1000.0 / sampleRateHz).toInt()
            // 宽区间 300-2500ms（24-200bpm），保留早搏短 RR 和代偿间隙长 RR
            if (rrMs in 300..2500) rr.add(rrMs)
        }
        return rr
    }

    /**
     * 剔除早搏 RR（偏离均值>20%），专供 HRV 计算
     *
     * 早搏的短 RR（联律间期）和长 RR（代偿间隙）会暴涨 SDNN/RMSSD/pNN50，
     * 让 DS 误判房颤。HRV 反映自主神经张力，应基于正常窦性心拍。
     * 节律判别仍用原始 RR（保早搏特征），两条通路各司其职。
     *
     * 阈值依据：Karey 2019 (Front Physiol, PMC6562196) ROC 分析显示
     * 20% 变化是区分正常/异常 RR 的灵敏-特异最佳平衡点（accuracy 0.92-0.99）。
     * 该文献用"相邻 RR 差>20%"，本实现改用"偏离均值>20%"——对 30 秒短记录更稳
     * （相邻差对单点噪声敏感，均值法平滑一次），阈值参考文献。
     */
    private fun filterEctopicBeats(rrIntervals: List<Int>): List<Int> {
        if (rrIntervals.size < 5) return rrIntervals
        val mean = rrIntervals.average()
        if (mean <= 0) return rrIntervals
        return rrIntervals.filter { rr ->
            val dev = abs(rr - mean) / mean
            dev <= 0.20  // Karey 2019：20% 是正常/异常 RR 的最佳分界点
        }.ifEmpty { rrIntervals }  // 全部偏离则保留原样（异常情况不空手）
    }

    /** 中位数（抗 P/T 波干扰，比均值更适合做局部基线估计） */
    private fun List<Int>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = this.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    private data class HeartRateStats(val avgHr: Int, val minHr: Int, val maxHr: Int)

    /**
     * 心率统计（抗 R 波误检 + 抗早搏设计）
     *
     * 已知问题（已修复）：旧实现用 P10-P90 分位数，30 秒短记录仅 ~40 个 RR，
     * 10% 误检就能把 P90 拉到虚高值（如真实 80bpm 测出 120bpm）。
     *
     * 策略（全部有文献依据）：
     * 1. avgHr 用中位数 RR 反算（60000/medianRR）——中位数对 ≤49% 的误检/早搏完全免疫
     *    （数学性质：中位数不受极端值影响，除非极端值过半）
     * 2. 心率范围基于呼吸性窦性心律不齐的正常变异幅度：
     *    Hiss 1976 (J Appl Physiol, PMID 993161): Percent variation = 23.2 - 0.35 × age
     *    - 18岁 ≈ 17%，40岁 ≈ 9%，年轻人呼吸性变异显著
     *    - 未知年龄用 ±20%（覆盖文献报告的年轻人最大变异 17% + 3% 余量）
     *    超过此范围的 RR 视为早搏/误检/噪声，不纳入心率范围统计
     * 3. 输入应为已剔早搏的 RR（filterEctopicBeats），避免短长 RR 污染心率
     */
    private fun computeHeartRateStats(rrIntervals: List<Int>, ageYears: Int): HeartRateStats {
        if (rrIntervals.size < 3) return HeartRateStats(0, 0, 0)
        val medianRR = rrIntervals.median()
        if (medianRR <= 0) return HeartRateStats(0, 0, 0)
        val medianHR = (60000.0 / medianRR).toInt()
        if (medianHR !in 40..200) return HeartRateStats(0, 0, 0)

        // 呼吸性窦性心律不齐的正常变异范围（Hiss 1976 公式）
        // 未知年龄(ageYears<=0)用 20%（覆盖年轻人最大变异 17% + 余量）
        val variationPct = if (ageYears > 0) {
            (23.2 - 0.35 * ageYears).coerceIn(5.0, 20.0) / 100.0
        } else {
            0.20
        }
        val lowerBound = medianRR * (1 - variationPct)
        val upperBound = medianRR * (1 + variationPct)
        val normalRRs = rrIntervals.filter { it.toDouble() in lowerBound..upperBound }
        val rrForRange = if (normalRRs.size >= 3) normalRRs else rrIntervals

        val minRR = (rrForRange.minOrNull() ?: medianRR.toInt()).toDouble()
        val maxRR = (rrForRange.maxOrNull() ?: medianRR.toInt()).toDouble()
        return HeartRateStats(
            avgHr = medianHR,  // 中位数反算，抗误检
            minHr = (60000.0 / maxRR).toInt(),  // 长 RR = 低心率
            maxHr = (60000.0 / minRR).toInt(),  // 短 RR = 高心率
        )
    }

    private data class HrvStats(val sdnn: Float, val rmssd: Float, val pnn50: Float)

    /**
     * HRV 时域指标（Task Force 1996 标准，Circulation 93:1043-1065）
     * - SDNN: 所有 NN 间期标准差，反映总体 HRV（短期记录反映交感+副交感+体液）
     * - RMSSD: 相邻 NN 差的均方根，反映副交感（迷走）张力，高频
     * - pNN50: 相邻 NN 差>50ms 占比，与 RMSSD 高度相关，反映副交感
     * Task Force 1996 明确：NN = 剔除非窦性心拍后的 RR，本实现已用 filterEctopicBeats 剔早搏
     * 注意：Task Force 推荐 5 分钟/24 小时记录，本设备 30 秒记录 SDNN 仅供参考不可比标准值
     */
    private fun computeHrv(rrIntervals: List<Int>): HrvStats {
        if (rrIntervals.size < 3) return HrvStats(0f, 0f, 0f)
        val mean = rrIntervals.average()
        // SDNN: 所有 RR 间期的标准差
        val variance = rrIntervals.map { (it - mean) * (it - mean) }.average()
        val sdnn = sqrt(variance).toFloat()
        // RMSSD: 相邻 RR 差的均方根
        var sumSqDiff = 0.0
        for (i in 1 until rrIntervals.size) {
            val diff = (rrIntervals[i] - rrIntervals[i - 1]).toDouble()
            sumSqDiff += diff * diff
        }
        val rmssd = sqrt(sumSqDiff / (rrIntervals.size - 1)).toFloat()
        // pNN50: 相邻 RR 差 >50ms 的占比
        var nn50 = 0
        for (i in 1 until rrIntervals.size) {
            if (abs(rrIntervals[i] - rrIntervals[i - 1]) > 50) nn50++
        }
        val pnn50 = (nn50.toFloat() / (rrIntervals.size - 1)) * 100f
        return HrvStats(sdnn, rmssd, pnn50)
    }

    /**
     * 节律判别辅助特征（帮助 DS 区分窦性/房颤/早搏）
     *
     * - RR 变异系数 CV = SDNN/meanRR：
     *   * <0.05 窦性心律（很规律）
     *   * 0.05~0.15 窦性心律不齐/呼吸性变异（青年人常见，深呼吸时可达 0.20）
     *   * >0.20 高度不规律，提示房颤或心律不齐（须结合 Poincaré 形态，不可仅凭 CV 判房颤）
     * - Poincaré 散点形态（RRn vs RRn+1）：
     *   * 彗星形(comet)：窄而长，沿对角线 → 窦性心律
     *   * 扇形(fan)：宽而散，远离对角线 → 房颤
     *   * 鱼雷形(torpedo)：窄短 → 窦性心律不齐
     *   * 复杂形(complex)：多个簇 → 早搏/异位心律
     * - 短-长 RR 配对：相邻 RR 中"短(前)<均值×0.8 且 长(后)>均值×1.2"
     *   是早搏代偿间隙的典型特征
     */
    private data class RhythmFeatures(
        val variabilityCoef: Float,
        val poincarePattern: String,
        val shortLongPairs: Int,
    )

    private fun computeRhythmFeatures(rrIntervals: List<Int>): RhythmFeatures {
        if (rrIntervals.size < 4) {
            return RhythmFeatures(0f, "数据不足", 0)
        }
        val mean = rrIntervals.average()
        if (mean < 1.0) return RhythmFeatures(0f, "数据不足", 0)

        // 变异系数
        val variance = rrIntervals.map { (it - mean) * (it - mean) }.average()
        val sdnn = sqrt(variance)
        val cv = (sdnn / mean).toFloat()

        // Poincaré 散点 SD1/SD2（Brennan 2001 标准公式）
        // SD1（短轴，垂直对角线，短期变异/副交感）= std(RR_n - RR_{n+1}) / sqrt(2)
        // SD2（长轴，沿对角线，长期变异）= std(RR_n + RR_{n+1}) / sqrt(2)
        val pairs = ArrayList<Pair<Double, Double>>()
        for (i in 0 until rrIntervals.size - 1) {
            pairs.add(Pair(rrIntervals[i].toDouble(), rrIntervals[i + 1].toDouble()))
        }
        val diffs = DoubleArray(pairs.size) { pairs[it].first - pairs[it].second }
        val sums = DoubleArray(pairs.size) { pairs[it].first + pairs[it].second }
        val diffMean = diffs.average()
        val sumMean = sums.average()
        var diffVar = 0.0
        var sumVar = 0.0
        for (i in diffs.indices) {
            diffVar += (diffs[i] - diffMean) * (diffs[i] - diffMean)
            sumVar += (sums[i] - sumMean) * (sums[i] - sumMean)
        }
        diffVar /= diffs.size
        sumVar /= sums.size
        val sd1 = sqrt(diffVar / 2.0)  // 短轴（短期变异）
        val sd2 = sqrt(sumVar / 2.0)   // 长轴（长期变异）
        // SD1/SD2 比值：SD1=短轴(副交感/短期变异)，SD2=长轴(长期变异)，Brennan 2001 标准公式
        // 方向性依据：健康人 SD1<SD2（彗星形，沿对角线集中），房颤时 SD1 显著升高接近甚至超过 SD2（扇形，短轴变宽）
        // 注意：文献中房颤检测多用 SVM/KNN 等机器学习分类（准确率 91-99%，Park 2009; Tuboly 2019），
        // 无统一的 SD1/SD2 比值金标准切点，此处用比值升高方向辅助 CV 判断，不作单一阈值诊断
        val ratio = if (sd2 > 0.1) sd1 / sd2 else 0.0

        // 短-长 RR 配对（早搏代偿间隙）
        // 生理学依据：早搏=短联律间期(早搏前RR短)+代偿间隙(早搏后RR长)，室早完全代偿/房早不完全代偿
        // 0.8/1.2 倍均值为经验切点（方向依据生理学，倍数无文献金标准）
        var shortLongPairs = 0
        for (i in 0 until rrIntervals.size - 1) {
            val isShort = rrIntervals[i] < mean * 0.8
            val isLong = rrIntervals[i + 1] > mean * 1.2
            if (isShort && isLong) shortLongPairs++
        }

        // 散点形态分类（综合考虑 CV、SD1/SD2 比值方向、短-长配对数）
        // CV 阈值 0.05/0.15/0.20 为经验值（文献无统一 CV 房颤切点，各研究用 RCV+SVM 分类）
        // SD1/SD2 比值仅用方向（升高=不规律），不用精确切点（无文献金标准）
        // 短-长配对数≥3 才提示早搏（1-2 个可能是正常呼吸性变异，经验值）
        val pattern = when {
            cv < 0.05 -> "彗星形(规律)"
            cv < 0.15 && ratio < 0.5 -> "彗星形(规律)"
            cv < 0.15 && ratio >= 0.5 -> "鱼雷形(轻度不齐)"
            cv in 0.15..0.20 && ratio >= 0.7 -> "扇形(不规律，需结合RR序列判断)"
            cv > 0.20 && ratio >= 0.7 -> "扇形(高度不规律，疑似房颤)"
            shortLongPairs >= 3 && cv < 0.20 -> "复杂形(疑似早搏)"
            else -> "彗星形(规律)"
        }

        return RhythmFeatures(cv, pattern, shortLongPairs)
    }

    private data class IntervalEstimates(
        val qrsMean: Int, val qrsStd: Int,
        val prMean: Int, val prStd: Int,
        val qtMean: Int, val qtc: Int,
        val qtcFridericiaMs: Int,
    )

    /**
     * 间期估测（精度有限，约 ±15ms 误差）
     *
     * QRS: 阈值交叉法——以 R 峰幅度的 10% 为阈值，找上升/下降穿越点作为 Q/S 边界。
     *      10% 比斜率法/25% 更接近临床 QRS 起止点，偏差 0-10ms（25% 只抓 R 尖峰偏窄 5-15ms）。
     * PR: R 峰前推 250ms 窗口找 P 波候选（小幅正向峰）→ 到 R 峰的间隔
     * QT: R 峰后 100~500ms 窗口（但不超过下一 R 峰前 50ms），去基线后用平滑导数找 T 波峰
     */
    private fun estimateIntervals(
        ecgData: List<Int>,
        sampleRateHz: Int,
        rPeaks: List<Int>,
        avgHr: Int,
    ): IntervalEstimates {
        if (rPeaks.size < 3) return IntervalEstimates(0, 0, 0, 0, 0, 0, 0)

        val qrsWidths = mutableListOf<Int>()
        val prIntervals = mutableListOf<Int>()
        val qtIntervals = mutableListOf<Int>()

        for (idx in rPeaks.indices) {
            val rIdx = rPeaks[idx]
            // QRS 估测：阈值交叉法（兼容正向/负向 R 波）
            val qSearchStart = maxOf(0, rIdx - sampleRateHz * 80 / 1000)
            val sSearchEnd = minOf(ecgData.size, rIdx + sampleRateHz * 80 / 1000)
            if (sSearchEnd - qSearchStart < 10) continue

            // 中位数去基线（抗 P/T 波干扰，比均值更适合局部基线估计，减小间期偏差）
            val segMedian = ecgData.subList(qSearchStart, sSearchEnd).median()
            // R 波幅度用绝对值，兼容负向 R 波（腕表单导联 R 波极性可能向下）
            val rAmpVal = abs(ecgData[rIdx].toDouble() - segMedian)
            if (rAmpVal < 30.0) continue  // R 波太小（<0.03mV），不可靠

            // 判断 R 波极性：R 峰值相对基线是正还是负
            val rIsPositive = ecgData[rIdx].toDouble() > segMedian

            // QRS 估测：阈值交叉法（工程经验实现，非临床金标准）
            // 临床 QRS delineation 金标准：小波变换（Martinez 2004）或 Pan-Tompkins 包络法
            // 本设备算力有限用阈值交叉法：以 R 峰幅度 10% 为阈值找 Q/S 边界
            // 10% 阈值是工程经验值（无文献金标准，临床多用导数零交叉或 wavelet）
            // 相对专业 API 可能偏宽 0-10ms（10% 阈值比 25% 更接近 PQ 交界→ST 起点）
            // 不用导数零交叉法：腕表单导联噪声大，导数多次过零导致 Q/S 定位不稳定
            val qrsThreshold = rAmpVal * 0.10

            // 找 Q 点：R 峰前第一个幅度低于阈值的点（从基线穿越阈值处）
            var qPoint = rIdx
            for (i in rIdx downTo qSearchStart) {
                val amp = abs(ecgData[i].toDouble() - segMedian)
                if (amp < qrsThreshold) {
                    qPoint = i
                    break
                }
            }
            // 找 S 点：R 峰后第一个幅度低于阈值的点
            var sPoint = rIdx
            for (i in rIdx until sSearchEnd) {
                val amp = abs(ecgData[i].toDouble() - segMedian)
                if (amp < qrsThreshold) {
                    sPoint = i
                    break
                }
            }
            val qrsMs = (sPoint - qPoint) * 1000 / sampleRateHz
            if (qrsMs in 40..200) qrsWidths.add(qrsMs)

            // PR 估测：R 峰前推 250ms 找 P 波（工程实现，非临床金标准）
            // 临床 P 波 delineation 金标准：小波变换（Martinez 2004），本设备算力有限用简化法
            // PR 正常范围 120-200ms（AHA/ESC 标准），P 波在 QRS 前 80-300ms 窗口内
            // 腕表单导联 P 波常不可辨，PR 估测误差大，提示词已标注"仅作参考"
            val pSearchStart = maxOf(0, rIdx - sampleRateHz * 250 / 1000)
            if (pSearchStart < qSearchStart) {
                val pSeg = ecgData.subList(pSearchStart, qSearchStart)
                if (pSeg.isNotEmpty()) {
                    // P 波通常与 R 波同极性，找同极性的局部最大偏离
                    val pIdx = pSeg.indices.maxByOrNull {
                        val dev = pSeg[it].toDouble() - segMedian
                        if (rIsPositive) dev else -dev
                    } ?: 0
                    val pPeakGlobal = pSearchStart + pIdx
                    val prMs = (rIdx - pPeakGlobal) * 1000 / sampleRateHz
                    if (prMs in 80..300) prIntervals.add(prMs)
                }
            }

            // QT 估测（简化实现，非临床金标准）
            // 临床金标准 QT = QRS 起点到 T 波"终点"，T 波终点用 Lepeschkin 切线法
            // （Postema 2014, PMID 24827793；Tzvi-Minker 2023）：T 波下降支最陡处切线与基线交点。
            // 本设备算力有限，用简化法：在去基线信号上找 T 波"峰"（绝对值最大极值点），
            // QT ≈ Q点到T波峰。这会系统性偏短（T 峰到 T 终点约 50-100ms），DS 提示词已告知
            // "QT 偏大 20-50ms" 的旧注释实际方向相反——本地估测偏短，但 DS 判长 QT 时更保守
            // 仍合理（偏短值若仍 >440ms，真实值更可能 >440ms，敏感度高）
            val tSearchStart = rIdx + sampleRateHz * 100 / 1000
            // 搜索窗口上限：min(rIdx+500ms, 下一R峰前50ms)
            // 高心率(HR≥120bpm, RR≤500ms)时不约束会跨入下一QRS，把下一R波当T波→QT虚高
            val tSearchHardEnd = rIdx + sampleRateHz * 500 / 1000
            val nextRPeakLimit = if (idx + 1 < rPeaks.size) rPeaks[idx + 1] - sampleRateHz * 50 / 1000
                                 else ecgData.size
            val tSearchEnd = minOf(ecgData.size, tSearchHardEnd, nextRPeakLimit)
            if (tSearchEnd > tSearchStart + 10) {
                val tSeg = ecgData.subList(tSearchStart, tSearchEnd)
                // 用窗口中位数去基线（比均值抗基线漂移）
                val tSorted = tSeg.sorted()
                val tMedian = tSorted[tSorted.size / 2].toDouble()
                // 平滑后求一阶导数，找导数过零点（T 波峰处导数由正转负或由负转正）
                val smoothWin = 5
                val smoothed = DoubleArray(tSeg.size)
                for (i in tSeg.indices) {
                    val from = maxOf(0, i - smoothWin / 2)
                    val to = minOf(tSeg.size, i + smoothWin / 2 + 1)
                    var sum = 0.0
                    for (j in from until to) sum += tSeg[j]
                    smoothed[i] = sum / (to - from) - tMedian
                }
                // 找去基线后绝对值最大的极值点作为 T 波峰（比原始极值法抗基线漂移）
                // 注意：这是 T 波峰，不是 T 波终点。QT 临床定义为到 T 波终点，
                // 本简化法测到 T 峰会偏短，已在上方注释说明
                var tIdx = -1
                var maxDev = 0.0
                for (i in smoothed.indices) {
                    if (i > 0 && i < smoothed.size - 1) {
                        val isPeak = (smoothed[i] > smoothed[i - 1] && smoothed[i] >= smoothed[i + 1]) ||
                            (smoothed[i] < smoothed[i - 1] && smoothed[i] <= smoothed[i + 1])
                        if (isPeak && abs(smoothed[i]) > maxDev) {
                            maxDev = abs(smoothed[i])
                            tIdx = i
                        }
                    }
                }
                if (tIdx >= 0) {
                    val tPeakGlobal = tSearchStart + tIdx
                    val qtMs = (tPeakGlobal - qPoint) * 1000 / sampleRateHz
                    if (qtMs in 250..600) qtIntervals.add(qtMs)
                }
            }
        }

        fun mean(lst: List<Int>) = if (lst.isEmpty()) 0 else lst.average().toInt()
        fun std(lst: List<Int>): Int {
            if (lst.size < 2) return 0
            val m = lst.average()
            return sqrt(lst.map { (it - m) * (it - m) }.average()).toInt()
        }

        val qtMean = mean(qtIntervals)
        // QTc 双公式（均有原始文献）：
        // - Bazett 1920 (Heart 7:353-370): QT/sqrt(RR)，60bpm 附近最准，HR>100 高估，HR<50 低估
        // - Fridericia 1920 (Acta Med Scand 53:469-486): QT/RR^(1/3)，50-90bpm 全区间误差小
        // 临床共识（Postema 2014, PMID 24827793）：Fridericia 心率波动大时比 Bazett 更稳
        val rrSec = if (avgHr > 0) 60.0 / avgHr else 0.0
        val qtc = if (qtMean > 0 && rrSec > 0) {
            (qtMean / sqrt(rrSec)).toInt()
        } else 0
        val qtcFridericia = if (qtMean > 0 && rrSec > 0) {
            // RR^(1/3) 用立方根近似：Math.cbrt
            (qtMean / Math.cbrt(rrSec)).toInt()
        } else 0

        return IntervalEstimates(
            qrsMean = mean(qrsWidths), qrsStd = std(qrsWidths),
            prMean = mean(prIntervals), prStd = std(prIntervals),
            qtMean = qtMean, qtc = qtc, qtcFridericiaMs = qtcFridericia,
        )
    }

    /** R 波平均振幅（mV），兼容正向/负向 R 波 */
    private fun computeRAmplitude(ecgData: List<Int>, rPeaks: List<Int>, sampleRateHz: Int): Float {
        if (rPeaks.isEmpty()) return 0f
        val window = sampleRateHz / 20  // ±50ms
        val amps = rPeaks.mapNotNull { rIdx ->
            val lo = maxOf(0, rIdx - window)
            val hi = minOf(ecgData.size, rIdx + window)
            if (hi > lo) {
                val seg = ecgData.subList(lo, hi)
                val segMean = seg.average()
                // 腕表单导联 R 波极性可能向下，取正向峰值与负向峰值中绝对值较大者
                // （与间期估测的 abs(ecgData[rIdx] - segMedian) 一致）
                val maxDev = maxOf(
                    abs((seg.maxOrNull() ?: 0) - segMean),
                    abs((seg.minOrNull() ?: 0) - segMean),
                )
                maxDev.toFloat() / 1000f  // mV×1000 → mV
            } else null
        }
        return if (amps.isNotEmpty()) amps.average().toFloat() else 0f
    }

    /** 逐秒分段特征提取 */
    private fun extractSegments(
        ecgData: List<Int>,
        sampleRateHz: Int,
        rPeaks: List<Int>,
        durationSec: Float,
    ): List<SegmentFeature> {
        val segLenSamples = sampleRateHz  // 1 秒一段（主流选择，0.5秒在70bpm下大量段跨RR间变0制造噪声）
        val segCount = (ecgData.size / segLenSamples).coerceAtLeast(1)
        val segments = mutableListOf<SegmentFeature>()

        for (segIdx in 0 until segCount) {
            val startSample = segIdx * segLenSamples
            val endSample = minOf(ecgData.size, (segIdx + 1) * segLenSamples)
            if (endSample - startSample < sampleRateHz / 2) continue  // 不足半秒跳过

            val seg = ecgData.subList(startSample, endSample)
            val segMean = seg.average()
            val centered = seg.map { (it - segMean).toFloat() / 1000f }  // mV

            val ampMin = centered.minOrNull() ?: 0f
            val ampMax = centered.maxOrNull() ?: 0f
            val p2p = ampMax - ampMin

            // 最大斜率（mV/s）
            var maxSlope = 0f
            for (i in 1 until centered.size) {
                val slope = abs(centered[i] - centered[i - 1]) * sampleRateHz.toFloat()
                if (slope > maxSlope) maxSlope = slope
            }

            // RMS
            val rms = sqrt(centered.map { it * it }.average()).toFloat()

            // 该段内 R 波数
            val rCount = rPeaks.count { it in startSample until endSample }

            segments.add(SegmentFeature(
                startSec = startSample.toFloat() / sampleRateHz,
                endSec = endSample.toFloat() / sampleRateHz,
                rPeakCount = rCount,
                ampMinMv = ampMin,
                ampMaxMv = ampMax,
                peakToPeakMv = p2p,
                maxSlopeMvPerSec = maxSlope,
                rmsMv = rms,
            ))
        }
        return segments
    }

    /** 信号质量评分（0~1） */
    private fun computeSignalQuality(
        segments: List<SegmentFeature>,
        rPeakCount: Int,
        durationSec: Float,
    ): Float {
        if (segments.isEmpty()) return 0f
        // 因子1：噪声段占比（越少越好）
        val noiseRatio = segments.count { it.rmsMv < 0.10f }.toFloat() / segments.size
        val noiseScore = 1f - noiseRatio
        // 因子2：期望心率 60-100bpm，30秒应有 30~50 个 R 波
        val expectedMin = (durationSec * 1f).toInt()   // 30bpm × 0.5
        val expectedMax = (durationSec * 2f).toInt()    // 120bpm
        val hrScore = if (rPeakCount in expectedMin..expectedMax) 1f else 0.5f
        // 因子3：平均 RMS 在合理范围（0.1~2.0mV）
        val avgRms = segments.map { it.rmsMv }.average().toFloat()
        val rmsScore = if (avgRms in 0.1f..2.0f) 1f
                       else if (avgRms in 0.05f..3.0f) 0.5f
                       else 0f
        return (noiseScore * 0.4f + hrScore * 0.3f + rmsScore * 0.3f).coerceIn(0f, 1f)
    }

    /**
     * 把特征打包成喂给 DeepSeek 的文本（结构化，约 1500~2500 token）
     */
    fun toPromptText(bundle: FeatureBundle): String {
        val g = bundle.global
        val sb = StringBuilder()

        // 用户属性
        sb.append("[用户属性]\n")
        if (bundle.profile.ageYears > 0) sb.append("年龄: ${bundle.profile.ageYears}岁\n")
        if (bundle.profile.isMale != null) {
            sb.append("性别: ${if (bundle.profile.isMale) "男" else "女"}\n")
        }
        sb.append("\n")

        // 全局指标
        sb.append("[全局指标]\n")
        sb.append("采样率:${g.sampleRateHz}Hz 时长:${"%.1f".format(g.durationSec)}s 总点数:${g.totalSamples}\n")
        sb.append("R波检测:${g.rPeakCount}个 平均心率:${g.avgHeartRate}bpm 范围:${g.minHeartRate}-${g.maxHeartRate}bpm\n")
        // PPG 绿光参考心率：测后读系统心率传感器，绕开本地 R 波检测可能的不准
        // DS 应以此为"金标准参考"校验本地 R 波心率：两者偏差大时本地 R 波检测可能漏检/误检
        if (g.ppgReferenceHr > 0) {
            sb.append("PPG绿光参考心率:${g.ppgReferenceHr}bpm(系统心率传感器独立测算，可信度高于本地R波估测；若与R波心率偏差>15bpm，提示R波检测可能漏检/误检，应以PPG为准)\n")
        }
        // R 波位置索引（样本索引）：方便助手精确复现 R 波检测，对照 HeartVoice API 差异
        // 导出诊断包时此字段是定位"本地算法漏检/误检哪几个 R 波"的关键证据
        if (g.rPeakIndices.isNotEmpty()) {
            sb.append("R波位置(样本索引):${g.rPeakIndices.joinToString(",")}\n")
        }
        // RR 序列标注异常值（偏离均值>20% 标*，与 filterEctopicBeats 阈值一致，Karey 2019）
        // 帮助 DS 直观识别早搏（短RR）和代偿间隙（长RR）
        if (g.rrIntervalsMs.isNotEmpty()) {
            val rrMean = g.rrIntervalsMs.average()
            val rrAnnotated = g.rrIntervalsMs.joinToString(" ") { rr ->
                val dev = if (rrMean > 0) abs(rr - rrMean) / rrMean else 0.0
                if (dev > 0.20) "[$rr*]" else "$rr"
            }
            sb.append("RR间期序列(ms，[*]为偏离均值>20%的异常间期，多为早搏/误检):$rrAnnotated\n")
            // RR 差值序列：正值=RR变长，负值=RR变短；大幅正负交替=短-长配对=早搏特征
            if (g.rrIntervalsMs.size >= 2) {
                val rrDiffs = (1 until g.rrIntervalsMs.size).joinToString(",") {
                    (g.rrIntervalsMs[it] - g.rrIntervalsMs[it - 1]).toString()
                }
                sb.append("RR差值序列(ms):$rrDiffs\n")
            }
        }
        sb.append("SDNN:${"%.1f".format(g.sdnnMs)}ms RMSSD:${"%.1f".format(g.rmssdMs)}ms pNN50:${"%.1f".format(g.pnn50Pct)}%\n")
        sb.append("QRS宽度(本地估测,ms):${g.qrsWidthMs}±${g.qrsWidthStdMs}\n")
        sb.append("PR间期(本地估测,ms):${g.prIntervalMs}±${g.prIntervalStdMs}\n")
        sb.append("QT间期(本地估测,ms):${g.qtIntervalMs} QTc(Bazett):${g.qtcMs}ms QTc(Fridericia):${g.qtcFridericiaMs}ms\n")
        sb.append("R波平均振幅:${"%.2f".format(g.rAmplitudeMv)}mV\n")
        sb.append("信号质量:${"%.2f".format(g.signalQuality)} ${if (g.signalQuality >= 0.7f) "良好" else if (g.signalQuality >= 0.4f) "一般" else "较差"}\n")
        if (g.noiseSegments.isNotEmpty()) {
            sb.append("噪声段:${g.noiseSegments.joinToString("; ")}\n")
        }
        sb.append("\n")

        // 节律判别特征（帮助区分窦性/房颤/早搏，基于 RR 间期序列）
        sb.append("[节律判别特征]\n")
        sb.append("RR变异系数:${"%.3f".format(g.rrVariabilityCoef)}")
        sb.append("(${if (g.rrVariabilityCoef < 0.05f) "规律" else if (g.rrVariabilityCoef < 0.15f) "轻度不齐" else "高度不规律"})\n")
        sb.append("Poincaré散点形态:${g.poincarePattern}\n")
        sb.append("短-长RR配对数(早搏代偿):${g.shortLongPairs}个\n")
        sb.append("\n")

        // 逐秒分段表
        sb.append("[逐秒分段]\n")
        sb.append("时段(s)  R波  振幅范围(mV)  峰峰值(mV)  最大斜率(mV/s)  RMS(mV)\n")
        for ((idx, seg) in bundle.segments.withIndex()) {
            val range = "${"%.2f".format(seg.ampMinMv)}~${"%.2f".format(seg.ampMaxMv)}"
            val prevSeg = bundle.segments.getOrNull(idx - 1)
            val nextSeg = bundle.segments.getOrNull(idx + 1)
            val note = when {
                seg.rmsMv < 0.10f -> "  ← 噪声段"
                // 2个R波+下一秒0个R波=短RR+代偿间隙，才是早搏特征
                // 70bpm时RR≈857ms，每6秒自然有一个1秒桶含2个R波，属正常
                seg.rPeakCount == 2 && nextSeg != null && nextSeg.rPeakCount == 0 ->
                    "  ← 短RR+代偿间隙(早搏可能)"
                seg.rPeakCount == 0 && prevSeg != null && prevSeg.rPeakCount == 2 ->
                    "  ← 代偿间隙(配合前秒短RR)"
                seg.rPeakCount == 0 && idx > 0 ->
                    "  ← 本秒无R波(心率<60或代偿间隙)"
                else -> ""
            }
            sb.append("${"%.1f".format(seg.startSec)}-${"%.1f".format(seg.endSec)}  ${seg.rPeakCount}  $range  ${"%.2f".format(seg.peakToPeakMv)}  ${"%.2f".format(seg.maxSlopeMvPerSec)}  ${"%.2f".format(seg.rmsMv)}$note\n")
        }
        // 逐秒 RMS 紧凑曲线：一行展示所有段的 RMS，方便快速识别噪声段分布
        // 与上方分段表对照，<0.10 的段即噪声段（已在 noiseSegments 标注）
        if (bundle.segments.isNotEmpty()) {
            sb.append("逐秒RMS:${bundle.segments.joinToString(",") { "%.3f".format(it.rmsMv) }}\n")
        }

        return sb.toString()
    }
}
