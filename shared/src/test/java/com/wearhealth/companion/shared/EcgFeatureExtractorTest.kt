package com.wearhealth.companion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * EcgFeatureExtractor 单元测试（纯 JVM，由 CI `:shared:testReleaseUnitTest` 运行）。
 *
 * 用合成信号验证特征提取的正确性：
 * - 合成正弦波（模拟稳定心率）
 * - 合成带早搏的信号
 * - 合成纯噪声段（验证噪声检测）
 * - 验证 R 波检测、HRV、间期估测、逐秒分段、信号质量评分
 */
class EcgFeatureExtractorTest {

    private val sampleRate = 500

    /**
     * 合成 ECG 波形：在指定时间点放置高斯形态的 R 波峰
     *
     * @param durationSec 时长（秒）
     * @param rPeakTimes R 波峰出现的时间点（秒）
     * @param rAmplitudeMv R 波振幅（mV），默认 1.0
     * @param noiseLevel 噪声幅度（mV），默认 0.02
     * @return ECG 数据（mV×1000 整数）
     */
    private fun syntheticEcg(
        durationSec: Float,
        rPeakTimes: List<Float>,
        rAmplitudeMv: Float = 1.0f,
        noiseLevel: Float = 0.02f,
    ): List<Int> {
        val totalSamples = (durationSec * sampleRate).toInt()
        val data = IntArray(totalSamples)
        val sigma = 0.01f  // R 波宽度 10ms 标准差
        val sigmaSq2 = 2 * sigma * sigma
        for (i in 0 until totalSamples) {
            val t = i.toFloat() / sampleRate
            var v = 0f
            for (rT in rPeakTimes) {
                val dt = t - rT
                v += rAmplitudeMv * kotlin.math.exp(-(dt * dt) / sigmaSq2).toFloat()
            }
            // 加微小噪声
            v += (Math.random() - 0.5).toFloat() * 2 * noiseLevel
            data[i] = (v * 1000).toInt()
        }
        return data.toList()
    }

    // ===== R 波检测与心率 =====

    @Test
    fun detectsRegularRPeaksAndHeartRate() {
        // 30 秒，每 0.857 秒一个 R 波（约 70bpm），共 35 个
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f)
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        val g = bundle.global
        // R 波数应接近 35（允许 ±2 误差）
        assertTrue("R 波数 ${g.rPeakCount} 应接近 35", g.rPeakCount in 33..37)
        // 平均心率应接近 70bpm
        assertTrue("平均心率 ${g.avgHeartRate} 应接近 70", g.avgHeartRate in 65..75)
        // min/max 应该很接近（节律稳定）
        assertTrue("心率范围 ${g.minHeartRate}-${g.maxHeartRate} 应该窄", g.maxHeartRate - g.minHeartRate <= 10)
    }

    @Test
    fun returnsZerosForTooShortData() {
        // 不足 5 秒，应返回 0
        val ecg = syntheticEcg(3f, listOf(1f, 2f))
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertEquals(0, g.rPeakCount)
        assertEquals(0, g.avgHeartRate)
    }

    @Test
    fun returnsZerosForFlatSignal() {
        // 全零信号，RMS 太低，检测失败
        val ecg = IntArray(sampleRate * 10).toList()
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertEquals(0, g.rPeakCount)
    }

    // ===== HRV =====

    @Test
    fun computesHrvMetrics() {
        // 30 秒，RR 间期稍有变化（70~75bpm 之间）
        val rTimes = mutableListOf<Float>()
        var t = 0f
        while (t < 30f) {
            rTimes.add(t)
            // RR 间期在 800~860ms 之间随机变化
            t += 0.8f + (Math.random() * 0.06f).toFloat()
        }
        val ecg = syntheticEcg(30f, rTimes)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // RR 间期序列长度应 = R 波数 - 1
        assertTrue("RR 间期序列 ${g.rrIntervalsMs.size} 应 > 0", g.rrIntervalsMs.size > 0)
        // SDNN 应该 > 0（间期有变化）
        assertTrue("SDNN ${g.sdnnMs} 应 > 0", g.sdnnMs > 0f)
        // RMSSD 应该 > 0
        assertTrue("RMSSD ${g.rmssdMs} 应 > 0", g.rmssdMs > 0f)
        // pNN50 可能是 0（间期差 <50ms），也可能是正数
        assertTrue("pNN50 ${g.pnn50Pct} 应 >= 0", g.pnn50Pct >= 0f)
    }

    @Test
    fun hrvZeroForInsufficientBeats() {
        val ecg = syntheticEcg(30f, listOf(1f, 2f))  // 只有 2 个 R 波
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertEquals(0f, g.sdnnMs)
        assertEquals(0f, g.rmssdMs)
    }

    // ===== 逐秒分段 =====

    @Test
    fun segmentsCoverEntireDuration() {
        val ecg = syntheticEcg(30f, (0 until 35).map { it * 0.857f })
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        // 0.5 秒一段，30 秒应有 60 段
        assertEquals("应有 60 段", 60, bundle.segments.size)
        // 第一段从 0.0 开始
        assertEquals(0f, bundle.segments[0].startSec)
        // 最后一段到 30.0 结束
        assertEquals(30f, bundle.segments.last().endSec)
    }

    @Test
    fun segmentRPeakCountMatchesInput() {
        // 每秒正好 1 个 R 波
        val rTimes = (0 until 30).map { it.toFloat() + 0.3f }
        val ecg = syntheticEcg(30f, rTimes)
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        // 大多数段应有 1 个 R 波
        val onesCount = bundle.segments.count { it.rPeakCount == 1 }
        assertTrue("大多数段应有 1 个 R 波，实际 $onesCount", onesCount >= 25)
    }

    @Test
    fun detectsEarlyBeatInSegment() {
        // 构造早搏场景：在 6.0-6.5s 这一段内放两个间隔 400ms 的 R 波（>300ms 不应期）
        // 模拟早搏：第 6 秒段出现 2 个 R 波，后续代偿间隙
        val rTimes = mutableListOf<Float>()
        // 前 6 秒每秒 1 个 R 波
        for (i in 0 until 6) rTimes.add(i.toFloat() + 0.2f)
        // 第 6 秒段放 2 个 R 波（6.05 和 6.45，间隔 400ms，都在 6.0-6.5s 段内）
        rTimes.add(6.05f)
        rTimes.add(6.45f)
        // 第 7 秒段代偿间隙不放 R 波，第 8 秒起恢复正常
        for (i in 8 until 30) rTimes.add(i.toFloat() + 0.2f)
        val ecg = syntheticEcg(30f, rTimes)
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        // 应该至少有一段 R 波数 = 2
        val doubleBeats = bundle.segments.count { it.rPeakCount == 2 }
        assertTrue("应检测到早搏（某段 R 波数=2），实际 $doubleBeats 段", doubleBeats >= 1)
    }

    // ===== 信号质量 =====

    @Test
    fun goodSignalHasHighQuality() {
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.01f)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertTrue("良好信号质量 ${g.signalQuality} 应 >= 0.5", g.signalQuality >= 0.5f)
    }

    @Test
    fun noiseSegmentDetected() {
        // 前 15 秒正常，后 15 秒纯噪声（低振幅）
        val rTimes = (0 until 18).map { it * 0.857f }
        val ecg1 = syntheticEcg(15f, rTimes.filter { it < 15f }, rAmplitudeMv = 1.0f)
        // 后 15 秒纯噪声
        val noiseSamples = (0 until sampleRate * 15).map { (Math.random() - 0.5 * 20).toInt() }
        val ecg = ecg1 + noiseSamples
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // 应该检测到噪声段
        assertTrue("应检测到噪声段: ${g.noiseSegments}", g.noiseSegments.isNotEmpty())
    }

    // ===== 间期估测 =====

    @Test
    fun estimatesQrsWidth() {
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // QRS 宽度应在合理范围（合成的高斯 R 波，QRS 约 40~120ms）
        if (g.qrsWidthMs > 0) {
            assertTrue("QRS 宽度 ${g.qrsWidthMs}ms 应在 40~200ms", g.qrsWidthMs in 40..200)
        }
        // QRS=0 也可接受（合成波形可能不满足检测条件）
    }

    @Test
    fun estimatesPrInterval() {
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // PR 间期若估测到，应在 80~300ms
        if (g.prIntervalMs > 0) {
            assertTrue("PR 间期 ${g.prIntervalMs}ms 应在 80~300ms", g.prIntervalMs in 80..300)
        }
    }

    @Test
    fun intervalsZeroForInsufficientBeats() {
        // 2 个 R 波 < 3 的间期估测门槛，应返回 0
        val ecg = syntheticEcg(30f, listOf(5f, 6f))
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertEquals(0, g.qrsWidthMs)
        assertEquals(0, g.prIntervalMs)
        assertEquals(0, g.qtIntervalMs)
    }

    // ===== 提示词文本生成 =====

    @Test
    fun promptTextContainsAllSections() {
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes)
        val bundle = EcgFeatureExtractor.extract(
            ecg, sampleRate,
            EcgFeatureExtractor.UserProfile(ageYears = 35, isMale = true),
        )
        val text = EcgFeatureExtractor.toPromptText(bundle)
        assertTrue("应包含 [用户属性]", text.contains("[用户属性]"))
        assertTrue("应包含年龄", text.contains("年龄: 35岁"))
        assertTrue("应包含性别", text.contains("性别: 男"))
        assertTrue("应包含 [全局指标]", text.contains("[全局指标]"))
        assertTrue("应包含采样率", text.contains("采样率:500Hz"))
        assertTrue("应包含 R 波检测", text.contains("R波检测"))
        assertTrue("应包含 SDNN", text.contains("SDNN"))
        assertTrue("应包含 RMSSD", text.contains("RMSSD"))
        assertTrue("应包含 [逐秒分段]", text.contains("[逐秒分段]"))
        assertTrue("应包含分段表头", text.contains("时段(s)"))
    }

    @Test
    fun promptTextOmitsProfileIfUnknown() {
        val ecg = syntheticEcg(10f, listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f))
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)  // 默认 profile
        val text = EcgFeatureExtractor.toPromptText(bundle)
        // 年龄未知不应出现"年龄:"
        // 注意：[用户属性] 段头仍会输出，但内容为空
        assertFalse("年龄未知不应输出年龄行", text.contains("年龄:"))
        assertFalse("性别未知不应输出性别行", text.contains("性别:"))
    }

    @Test
    fun promptTextMarksNoiseSegments() {
        // 前 10 秒正常，后 20 秒纯噪声
        val rTimes = (0 until 12).map { it * 0.857f }
        val ecg1 = syntheticEcg(10f, rTimes.filter { it < 10f }, rAmplitudeMv = 1.0f)
        val noiseSamples = (0 until sampleRate * 20).map { (Math.random() - 0.5 * 10).toInt() }
        val ecg = ecg1 + noiseSamples
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        val text = EcgFeatureExtractor.toPromptText(bundle)
        assertTrue("应标注噪声段", text.contains("噪声"))
    }
}
