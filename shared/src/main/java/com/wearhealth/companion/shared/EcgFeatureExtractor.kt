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
        val qtcMs: Int,                 // Bazett 校正
        val rAmplitudeMv: Float,        // R 波平均振幅
        val signalQuality: Float,       // 0~1
        val noiseSegments: List<String>,// 噪声段时段标记
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
            rAmplitudeMv = rAmp,
            signalQuality = sigQuality,
            noiseSegments = noiseSegs,
        )
        return FeatureBundle(global, segments, profile)
    }

    // ===== 内部算法 =====

    /** R 波检测（复用 computeMinMaxHeartRate 的算法，但返回 R 峰位置） */
    private fun detectRPeaks(ecgData: List<Int>, sampleRateHz: Int): List<Int> {
        if (ecgData.size < sampleRateHz * 5) return emptyList()

        // 带通滤波：1 秒窗口去基线
        val baselineWindow = sampleRateHz
        val baseline = DoubleArray(ecgData.size)
        for (i in ecgData.indices) {
            val from = maxOf(0, i - baselineWindow / 2)
            val to = minOf(ecgData.size, i + baselineWindow / 2)
            var sum = 0.0
            for (j in from until to) sum += ecgData[j]
            baseline[i] = sum / (to - from)
        }
        val highPassed = ecgData.mapIndexed { i, v -> v - baseline[i] }

        // 低通平滑：5 点移动平均
        val smoothWin = 5
        val filtered = DoubleArray(highPassed.size)
        for (i in highPassed.indices) {
            val from = maxOf(0, i - smoothWin / 2)
            val to = minOf(highPassed.size, i + smoothWin / 2 + 1)
            var sum = 0.0
            for (j in from until to) sum += highPassed[j]
            filtered[i] = sum / (to - from)
        }

        // 主峰极性自动选择
        val positivePeak = filtered.maxOrNull() ?: 0.0
        val negativePeak = abs(filtered.minOrNull() ?: 0.0)
        val polarity = if (negativePeak > positivePeak) -1.0 else 1.0
        val oriented = DoubleArray(filtered.size) { idx -> filtered[idx] * polarity }

        // 自适应阈值：用信号幅度的 75 分位数（避开基线段拉低 RMS 的问题）。
        // 之前用 rms*2.0，RMS 含大量平坦基线，阈值偏高导致正常 R 波被滤掉（31秒仅检出4个→8bpm）。
        val absSorted = oriented.map { abs(it) }.sorted()
        val p75 = if (absSorted.isNotEmpty()) absSorted[(absSorted.size * 0.75).toInt()] else 0.0
        // 阈值取 max(75分位数, 全局最大值的 30%)，保证 R 波不被基线噪声误触，又不漏检正常 R 波
        val peakMax = absSorted.lastOrNull() ?: 0.0
        val threshold = maxOf(p75, peakMax * 0.30)
        if (threshold < 20.0) return emptyList() // 数据几乎全平，无意义

        // R 峰检测
        val rPeaks = mutableListOf<Int>()
        val refractory = sampleRateHz / 3  // 300ms 不应期（最高 200bpm）
        var lastPeakIdx = -refractory * 2
        val checkRange = sampleRateHz / 50

        for (i in oriented.indices) {
            if (oriented[i] < threshold) continue
            val lo = maxOf(0, i - checkRange)
            val hi = minOf(oriented.size, i + checkRange + 1)
            var isLocalMax = true
            for (j in lo until hi) {
                if (j != i && oriented[j] > oriented[i]) { isLocalMax = false; break }
            }
            if (!isLocalMax) continue
            // 斜率门槛放宽：只要 R 峰相对 50ms 前有上升即可（原 threshold*0.5 过严）
            val slopeIdx = maxOf(0, i - sampleRateHz / 20)
            val slope = oriented[i] - oriented[slopeIdx]
            if (slope < threshold * 0.2) continue
            if ((i - lastPeakIdx) < refractory) continue
            rPeaks.add(i)
            lastPeakIdx = i
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
        return HeartRateStats(
            avgHr = filtered.average().toInt(),
            minHr = filtered.min(),
            maxHr = filtered.max(),
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

    private data class IntervalEstimates(
        val qrsMean: Int, val qrsStd: Int,
        val prMean: Int, val prStd: Int,
        val qtMean: Int, val qtc: Int,
    )

    /**
     * 间期估测（精度有限，约 ±15ms 误差）
     *
     * QRS: R 峰前后各 60ms 窗口，找最大上升斜率点（Q 起点）和最大下降斜率点（S 终点）
     * PR: R 峰前推 250ms 窗口找 P 波候选（小幅正向峰）→ 到 R 峰的间隔
     * QT: R 峰后 100~500ms 窗口找 T 波峰，T 波结束点（斜率回零）到 QRS 起点
     */
    private fun estimateIntervals(
        ecgData: List<Int>,
        sampleRateHz: Int,
        rPeaks: List<Int>,
        avgHr: Int,
    ): IntervalEstimates {
        if (rPeaks.size < 3) return IntervalEstimates(0, 0, 0, 0, 0, 0)

        val qrsWidths = mutableListOf<Int>()
        val prIntervals = mutableListOf<Int>()
        val qtIntervals = mutableListOf<Int>()

        for (rIdx in rPeaks) {
            // QRS 估测：R 峰前 60ms 找最大上升斜率点（Q），后 60ms 找最大下降斜率点（S）
            val qSearchStart = maxOf(0, rIdx - sampleRateHz * 60 / 1000)
            val sSearchEnd = minOf(ecgData.size, rIdx + sampleRateHz * 60 / 1000)
            if (sSearchEnd - qSearchStart < 10) continue

            // 去基线均值用于 T 波极性判断
            val segStart = qSearchStart
            val segEnd = sSearchEnd
            val segMean = ecgData.subList(segStart, segEnd).average()

            // 找 Q 点：R 峰前最大正向斜率的起点
            var qPoint = rIdx
            var maxSlope = 0.0
            for (i in qSearchStart until rIdx) {
                if (i + 5 < ecgData.size) {
                    val slope = abs((ecgData[i + 5] - ecgData[i]).toDouble())
                    if (slope > maxSlope) { maxSlope = slope; qPoint = i }
                }
            }
            // 找 S 点：R 峰后最大负向斜率的终点
            var sPoint = rIdx
            var maxNegSlope = 0.0
            for (i in rIdx until sSearchEnd) {
                if (i + 5 < ecgData.size) {
                    val slope = abs((ecgData[i + 5] - ecgData[i]).toDouble())
                    if (slope > maxNegSlope) { maxNegSlope = slope; sPoint = i + 5 }
                }
            }
            val qrsMs = (sPoint - qPoint) * 1000 / sampleRateHz
            if (qrsMs in 40..200) qrsWidths.add(qrsMs)

            // PR 估测：R 峰前推 250ms 找 P 波（小幅正向峰）
            val pSearchStart = maxOf(0, rIdx - sampleRateHz * 250 / 1000)
            if (pSearchStart < qSearchStart) {
                // 在 P 搜索窗口找局部最大值（P 波）
                val pSeg = ecgData.subList(pSearchStart, qSearchStart)
                if (pSeg.isNotEmpty()) {
                    val pIdx = pSeg.indices.maxByOrNull { pSeg[it] } ?: 0
                    val pPeakGlobal = pSearchStart + pIdx
                    val prMs = (rIdx - pPeakGlobal) * 1000 / sampleRateHz
                    if (prMs in 80..300) prIntervals.add(prMs)
                }
            }

            // QT 估测：R 峰后 100~500ms 找 T 波峰
            val tSearchStart = rIdx + sampleRateHz * 100 / 1000
            val tSearchEnd = minOf(ecgData.size, rIdx + sampleRateHz * 500 / 1000)
            if (tSearchEnd > tSearchStart) {
                val tSeg = ecgData.subList(tSearchStart, tSearchEnd)
                if (tSeg.isNotEmpty()) {
                    // T 波是局部最大值（正向 T 波）或最小值（负向 T 波），取绝对值最大的
                    val tMaxIdx = tSeg.indices.maxByOrNull { tSeg[it] }
                    val tMinIdx = tSeg.indices.minByOrNull { tSeg[it] }
                    val tIdx = if (tMaxIdx != null && tMinIdx != null) {
                        if (abs(tSeg[tMaxIdx] - segMean) > abs(tSeg[tMinIdx] - segMean)) tMaxIdx else tMinIdx
                    } else tMaxIdx ?: tMinIdx ?: continue
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
        // QTc = QT / sqrt(RR/1s)，Bazett 公式
        val qtc = if (qtMean > 0 && avgHr > 0) {
            val rrSec = 60.0 / avgHr
            (qtMean / sqrt(rrSec)).toInt()
        } else 0

        return IntervalEstimates(
            qrsMean = mean(qrsWidths), qrsStd = std(qrsWidths),
            prMean = mean(prIntervals), prStd = std(prIntervals),
            qtMean = qtMean, qtc = qtc,
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
        val segLenSamples = sampleRateHz  // 1 秒一段
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
        sb.append("RR间期序列(ms):${g.rrIntervalsMs.joinToString(",")}\n")
        sb.append("SDNN:${"%.1f".format(g.sdnnMs)}ms RMSSD:${"%.1f".format(g.rmssdMs)}ms pNN50:${"%.1f".format(g.pnn50Pct)}%\n")
        sb.append("QRS宽度(本地估测,ms):${g.qrsWidthMs}±${g.qrsWidthStdMs}\n")
        sb.append("PR间期(本地估测,ms):${g.prIntervalMs}±${g.prIntervalStdMs}\n")
        sb.append("QT间期(本地估测,ms):${g.qtIntervalMs} QTc:${g.qtcMs}\n")
        sb.append("R波平均振幅:${"%.2f".format(g.rAmplitudeMv)}mV\n")
        sb.append("信号质量:${"%.2f".format(g.signalQuality)} ${if (g.signalQuality >= 0.7f) "良好" else if (g.signalQuality >= 0.4f) "一般" else "较差"}\n")
        if (g.noiseSegments.isNotEmpty()) {
            sb.append("噪声段:${g.noiseSegments.joinToString("; ")}\n")
        }
        sb.append("\n")

        // 逐秒分段表
        sb.append("[逐秒分段]\n")
        sb.append("时段(s)  R波  振幅范围(mV)  峰峰值(mV)  最大斜率(mV/s)  RMS(mV)\n")
        for (seg in bundle.segments) {
            val range = "${"%.2f".format(seg.ampMinMv)}~${"%.2f".format(seg.ampMaxMv)}"
            val note = when {
                seg.rmsMv < 0.05f -> "  ← 噪声段"
                seg.rPeakCount == 2 -> "  ← 本秒2个R波(可能早搏)"
                seg.rPeakCount == 0 && bundle.segments.indexOf(seg) > 0 ->
                    "  ← 本秒无R波(可能代偿间隙)"
                else -> ""
            }
            sb.append("${"%.1f".format(seg.startSec)}-${"%.1f".format(seg.endSec)}  ${seg.rPeakCount}  $range  ${"%.2f".format(seg.peakToPeakMv)}  ${"%.2f".format(seg.maxSlopeMvPerSec)}  ${"%.2f".format(seg.rmsMv)}$note\n")
        }

        return sb.toString()
    }
}
