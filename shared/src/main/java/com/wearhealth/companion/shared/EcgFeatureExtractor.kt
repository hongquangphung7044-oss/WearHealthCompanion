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
    ): FeatureBundle {
        val durationSec = if (sampleRateHz > 0) ecgData.size.toFloat() / sampleRateHz else 0f
        val rPeaks = detectRPeaks(ecgData, sampleRateHz)
        val rrIntervals = computeRRIntervals(rPeaks, sampleRateHz)
        val hr = computeHeartRateStats(rrIntervals)
        val hrv = computeHrv(rrIntervals)
        val rhythm = computeRhythmFeatures(rrIntervals)
        val intervals = estimateIntervals(ecgData, sampleRateHz, rPeaks, hr.avgHr)
        val rAmp = computeRAmplitude(ecgData, rPeaks, sampleRateHz)
        val segments = extractSegments(ecgData, sampleRateHz, rPeaks, durationSec)
        val noiseSegs = segments.filter { it.rmsMv < 0.05f }
            .map { "${"%.1f".format(it.startSec)}-${"%.1f".format(it.endSec)}s(噪声)" }
        val sigQuality = computeSignalQuality(segments, rPeaks.size, durationSec)

        val global = GlobalFeatures(
            sampleRateHz = sampleRateHz,
            durationSec = durationSec,
            totalSamples = ecgData.size,
            rPeakCount = rPeaks.size,
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
     * 5. 局部最大 + 300ms 不应期
     * 6. 原始信号 ±25ms 精修 R 峰位置
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

        // 3. 50ms boxcar 平滑（形成 QRS 包络，减少噪声过零）
        val smoothWin = (sampleRateHz * 0.05).toInt().coerceAtLeast(3)  // 50ms
        val envelope = DoubleArray(gradient.size)
        for (i in gradient.indices) {
            val from = maxOf(0, i - smoothWin / 2)
            val to = minOf(gradient.size, i + smoothWin / 2 + 1)
            var sum = 0.0
            for (j in from until to) sum += gradient[j]
            envelope[i] = sum / (to - from)
        }

        // 4. 自适应阈值 = 均值 + 2×标准差（NeuroKit 风格统计阈值）
        val envMean = envelope.average()
        val envVar = envelope.map { (it - envMean) * (it - envMean) }.average()
        val envStd = sqrt(envVar)
        val threshold = envMean + envStd * 2.0
        if (threshold < 3.0) return emptyList()  // 梯度信号阈值较低

        // 5. R 峰检测：阈值 + 局部最大 + 不应期
        val rPeaks = mutableListOf<Int>()
        val refractory = sampleRateHz / 3  // 300ms 不应期（最高 200bpm）
        var lastPeakIdx = -refractory * 2
        val checkRange = sampleRateHz / 50  // ±10ms 局部最大检查

        for (i in envelope.indices) {
            if (envelope[i] < threshold) continue
            val lo = maxOf(0, i - checkRange)
            val hi = minOf(envelope.size, i + checkRange + 1)
            var isLocalMax = true
            for (j in lo until hi) {
                if (j != i && envelope[j] > envelope[i]) { isLocalMax = false; break }
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
            lastPeakIdx = i
        }

        // 7. 回溯补检（Pan-Tompkins 核心）：RR > 1.66×近期均值时可能有漏检
        if (rPeaks.size >= 5) {
            val extraPeaks = mutableListOf<Int>()
            for (k in 1 until rPeaks.size) {
                val rr = rPeaks[k] - rPeaks[k - 1]
                // 计算近期 RR 均值（前后各 4 个）
                val recentStart = maxOf(0, k - 4)
                val recentEnd = minOf(rPeaks.size, k + 4)
                val recentRRs = mutableListOf<Int>()
                for (m in recentStart + 1 until recentEnd) {
                    recentRRs.add(rPeaks[m] - rPeaks[m - 1])
                }
                if (recentRRs.size < 3) continue
                val rrAvg = recentRRs.average()
                if (rrAvg < 1.0) continue

                if (rr > rrAvg * 1.66) {
                    // 半阈值搜索漏检区间
                    val backThr = threshold * 0.5
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

    private fun computeRRIntervals(rPeaks: List<Int>, sampleRateHz: Int): List<Int> {
        if (rPeaks.size < 2) return emptyList()
        val rr = mutableListOf<Int>()
        for (i in 1 until rPeaks.size) {
            val rrMs = ((rPeaks[i] - rPeaks[i - 1]) * 1000.0 / sampleRateHz).toInt()
            if (rrMs in 400..1500) rr.add(rrMs)
        }
        return rr
    }

    /** 中位数（抗 P/T 波干扰，比均值更适合做局部基线估计） */
    private fun List<Int>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = this.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    private data class HeartRateStats(val avgHr: Int, val minHr: Int, val maxHr: Int)

    private fun computeHeartRateStats(rrIntervals: List<Int>): HeartRateStats {
        if (rrIntervals.size < 3) return HeartRateStats(0, 0, 0)
        val hrs = rrIntervals.map { (60000.0 / it).toInt() }.filter { it in 40..150 }
        if (hrs.size < 3) return HeartRateStats(0, 0, 0)
        // 剔除偏离中位数 ±30% 的异常值
        val sorted = hrs.sorted()
        val median = sorted[sorted.size / 2]
        val filtered = hrs.filter { hr ->
            abs(hr - median).toDouble() / median <= 0.3
        }
        if (filtered.isEmpty()) return HeartRateStats(0, 0, 0)
        // 用 P10-P90 分位数替代 min/max，排除早搏代偿间隙/瞬时噪声导致的极端值
        // 17岁窦性心律不齐 RR 可变 ±20%，min/max 会把呼吸性低峰当作最低心率
        val sortedFiltered = filtered.sorted()
        val p10Idx = (sortedFiltered.size * 0.10).toInt().coerceIn(0, sortedFiltered.lastIndex)
        val p90Idx = (sortedFiltered.size * 0.90).toInt().coerceIn(0, sortedFiltered.lastIndex)
        return HeartRateStats(
            avgHr = filtered.average().toInt(),
            minHr = sortedFiltered[p10Idx],
            maxHr = sortedFiltered[p90Idx],
        )
    }

    private data class HrvStats(val sdnn: Float, val rmssd: Float, val pnn50: Float)

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
     *   * 0.05~0.15 窦性心律不齐/呼吸性变异（青年人常见）
     *   * >0.15 高度不规律，提示房颤或心律不齐
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
        // SD1/SD2：健康人 <0.5（彗星形），房颤接近 1（扇形，短轴宽）
        val ratio = if (sd2 > 0.1) sd1 / sd2 else 0.0

        // 短-长 RR 配对（早搏代偿间隙）——先算，供散点形态分类使用
        var shortLongPairs = 0
        for (i in 0 until rrIntervals.size - 1) {
            val isShort = rrIntervals[i] < mean * 0.8
            val isLong = rrIntervals[i + 1] > mean * 1.2
            if (isShort && isLong) shortLongPairs++
        }

        // 散点形态分类（需同时考虑 CV、SD1/SD2 比值、短-长配对数）
        // 注意：短-长配对数≥3 才提示早搏（1-2 个可能是正常呼吸性变异）
        val pattern = when {
            cv < 0.05 -> "彗星形(规律)"
            cv < 0.15 && ratio < 0.5 -> "彗星形(规律)"
            cv < 0.15 && ratio >= 0.5 -> "鱼雷形(轻度不齐)"
            cv >= 0.15 && ratio >= 0.7 -> "扇形(高度不规律，疑似房颤)"
            shortLongPairs >= 3 && cv < 0.15 -> "复杂形(疑似早搏)"
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
     * QRS: 阈值交叉法——以 R 峰幅度的 25% 为阈值，找上升/下降穿越点作为 Q/S 边界。
     *      比斜率法更接近真实 QRS 宽度（腕表 R 波上升支斜率峰值偏靠 R 峰，斜率法会偏小）。
     * PR: R 峰前推 250ms 窗口找 P 波候选（小幅正向峰）→ 到 R 峰的间隔
     * QT: R 峰后 100~500ms 窗口，去基线后用平滑导数找 T 波峰，减少基线漂移误判
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

        for (rIdx in rPeaks) {
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

            // 阈值 = R 峰幅度的 10%（15% 只抓到 R 波尖峰附近，漏掉 Q/S 波导致 QRS 偏窄 5-15ms）
            // 10% 更接近临床 QRS 起止点（PQ 交界→ST 段起点），偏差缩小到 0-10ms
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

            // PR 估测：R 峰前推 250ms 找 P 波（小幅同极性峰）
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

            // QT 估测：去基线后用平滑导数找 T 波峰，减少基线漂移误判
            val tSearchStart = rIdx + sampleRateHz * 100 / 1000
            val tSearchEnd = minOf(ecgData.size, rIdx + sampleRateHz * 500 / 1000)
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
        // QTc 双公式：
        // - Bazett: QT/sqrt(RR) —— 60bpm 附近最准，HR>100 高估，HR<50 低估
        // - Fridericia: QT/RR^(1/3) —— 50-90bpm 全区间误差小，心率波动大时更稳
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

    /** R 波平均振幅（mV） */
    private fun computeRAmplitude(ecgData: List<Int>, rPeaks: List<Int>, sampleRateHz: Int): Float {
        if (rPeaks.isEmpty()) return 0f
        val window = sampleRateHz / 20  // ±50ms
        val amps = rPeaks.mapNotNull { rIdx ->
            val lo = maxOf(0, rIdx - window)
            val hi = minOf(ecgData.size, rIdx + window)
            if (hi > lo) {
                val seg = ecgData.subList(lo, hi)
                val segMean = seg.average()
                val peak = seg.maxOrNull() ?: 0
                (peak - segMean).toFloat() / 1000f  // mV×1000 → mV
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
        val noiseRatio = segments.count { it.rmsMv < 0.05f }.toFloat() / segments.size
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
        // RR 序列标注异常值（偏离均值>25% 标*），帮助 DS 直观识别早搏（短RR）和代偿间隙（长RR）
        if (g.rrIntervalsMs.isNotEmpty()) {
            val rrMean = g.rrIntervalsMs.average()
            val rrAnnotated = g.rrIntervalsMs.joinToString(" ") { rr ->
                val dev = if (rrMean > 0) abs(rr - rrMean) / rrMean else 0.0
                if (dev > 0.25) "[$rr*]" else "$rr"
            }
            sb.append("RR间期序列(ms，[*]为偏离均值>25%的异常间期):$rrAnnotated\n")
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
                seg.rmsMv < 0.05f -> "  ← 噪声段"
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

        return sb.toString()
    }
}
