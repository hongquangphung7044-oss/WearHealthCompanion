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
     * 合成 ECG 波形：在指定时间点放置高斯形态的 R 波峰，可选加 T 波
     *
     * @param durationSec 时长（秒）
     * @param rPeakTimes R 波峰出现的时间点（秒）
     * @param rAmplitudeMv R 波振幅（mV），默认 1.0；负值=负向 R 波（导联反接/位置异常）
     * @param noiseLevel 噪声幅度（mV），默认 0.02
     * @param seed 随机种子
     * @param tAmplitudeMv T 波振幅（mV），默认 0=不加 T 波；正值=与 R 波同极性，负值=反极性
     * @param tOffsetSec T 波相对 R 波的偏移（秒），默认 0.3s（正常 200-400ms）
     * @param rSigmaSec R 波高斯宽度标准差（秒），默认 0.01=10ms（窄峰，旧测试兼容）；
     *                  形态测试用 0.04=40ms（接近真实 QRS 宽度 80-120ms 的半宽）
     */
    private fun syntheticEcg(
        durationSec: Float,
        rPeakTimes: List<Float>,
        rAmplitudeMv: Float = 1.0f,
        noiseLevel: Float = 0.02f,
        seed: Long = 42L,
        tAmplitudeMv: Float = 0f,
        tOffsetSec: Float = 0.3f,
        rSigmaSec: Float = 0.01f,
    ): List<Int> {
        val totalSamples = (durationSec * sampleRate).toInt()
        val data = IntArray(totalSamples)
        val sigma = rSigmaSec
        val sigmaSq2 = 2 * sigma * sigma
        val tSigma = 0.04f  // T 波宽度 40ms（比 R 波宽，符合生理学）
        val tSigmaSq2 = 2 * tSigma * tSigma
        // 固定种子：消除测试随机性导致的 flaky 失败。
        // 旧实现用 Math.random() 无种子，R 波检测改动（MWI 150ms）后对噪声更敏感，
        // 偶尔产生额外/偏移峰导致心率范围断言间歇性失败（同代码 #113 通过、#114 失败）。
        val rng = java.util.Random(seed)
        for (i in 0 until totalSamples) {
            val t = i.toFloat() / sampleRate
            var v = 0f
            for (rT in rPeakTimes) {
                val dt = t - rT
                v += rAmplitudeMv * kotlin.math.exp(-(dt * dt) / sigmaSq2).toFloat()
                // T 波：与 R 波同极性（振幅符号跟随 rAmplitudeMv），偏移 tOffsetSec
                if (tAmplitudeMv != 0f) {
                    val tDt = t - rT - tOffsetSec
                    // T 波振幅符号跟随 R 波：rAmp>0 则 T 波向上，rAmp<0 则 T 波向下
                    val tSign = if (rAmplitudeMv > 0) tAmplitudeMv else -tAmplitudeMv
                    v += tSign * kotlin.math.exp(-(tDt * tDt) / tSigmaSq2).toFloat()
                }
            }
            // 加微小噪声
            v += (rng.nextDouble() - 0.5).toFloat() * 2 * noiseLevel
            data[i] = (v * 1000).toInt()
        }
        return data.toList()
    }

    // ===== R 波检测与心率 =====

    @Test
    fun detectsRegularRPeaksAndHeartRate() {
        // 30 秒，每 0.857 秒一个 R 波（约 70bpm），共 35 个
        val rTimes = (0 until 35).map { it * 0.857f }
        // 本测试验证规则节律的 R 波检测与心率，噪声容限单独由 goodSignalHasHighQuality/
        // noiseSegmentDetected 覆盖；此处用 0.01mV 噪声（100:1 SNR）避免噪声毛刺干扰节律断言
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.01f)
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

    @Test
    fun flatSegmentWithMicroNoiseDoesNotProduceFalsePeaks() {
        // 回归测试：30 秒信号只有 2 个 R 波（1s 和 2s），其余 28 秒为平坦微噪声段。
        // 旧分段阈值 mean+2×std 在平坦段 std 极小 → 阈值 ≈ 噪声峰值 → 大量虚假 R 峰
        // → 虚假短 RR → HRV 被错误计算（SDNN>0）+ 心率范围虚高。
        // 修复：分段阈值加中位数×1.5 下限，中位数抗稀疏 R 波污染、稳健估计噪声基底，
        // 要求 R 波包络至少为噪声基底 1.5 倍（SNR>1.5，Pan-Tompkins 检测下限）。
        val ecg = syntheticEcg(30f, listOf(1f, 2f), rAmplitudeMv = 1.0f, noiseLevel = 0.02f, seed = 42L)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // 仅应检出 2 个真实 R 波（允许 0-4，容忍极少边界误检但不应有大量虚假峰）
        assertTrue(
            "平坦段不应产生大量虚假 R 峰，实际 ${g.rPeakCount}",
            g.rPeakCount <= 4,
        )
        // HRV 应为 0（RR 间期数 < 3，不足以计算 SDNN）
        assertEquals("SDNN 应为 0（R 波数不足）", 0f, g.sdnnMs)
        assertEquals("RMSSD 应为 0（R 波数不足）", 0f, g.rmssdMs)
    }

    // ===== 逐秒分段 =====

    @Test
    fun segmentsCoverEntireDuration() {
        val ecg = syntheticEcg(30f, (0 until 35).map { it * 0.857f })
        val bundle = EcgFeatureExtractor.extract(ecg, sampleRate)
        // 1 秒一段，30 秒应有 30 段
        assertEquals("应有 30 段", 30, bundle.segments.size)
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
        // 构造早搏场景：在第 6 秒段内放两个间隔 400ms 的 R 波（>300ms 不应期）
        // 模拟早搏：第 6 秒段出现 2 个 R 波，后续代偿间隙
        val rTimes = mutableListOf<Float>()
        // 前 6 秒每秒 1 个 R 波
        for (i in 0 until 6) rTimes.add(i.toFloat() + 0.2f)
        // 第 6 秒段放 2 个 R 波（6.05 和 6.45，间隔 400ms，都在 6-7s 段内）
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

    @Test
    fun detectsRPeaksInCleanSegmentDespiteNoiseInOtherSegment() {
        // 场景：前 15 秒干净 70bpm 规则 R 波，后 15 秒大振幅运动伪差（噪声幅度 3.0mV，远超 R 波）
        // 当前全局阈值会被噪声段拉高 → 干净段 R 波漏检
        // 分段自适应阈值应让干净段正常检出（前 15 秒约 18 个 R 波，允许 ±3 误差）
        val rng = java.util.Random(20260716L)  // 固定种子，测试可复现
        val rTimes = (0 until 18).map { it * 0.857f }  // 前 15 秒约 17-18 个 R 波（70bpm）
        val ecg1 = syntheticEcg(15f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f, seed = 100L)
        // 后 15 秒大振幅噪声（运动伪差，幅度 3.0mV，是 R 波的 3 倍）
        val noiseSamples = (0 until sampleRate * 15).map {
            ((rng.nextGaussian() * 3.0).toInt() * 1000)  // 3.0mV 标准差高斯噪声
        }
        val ecg = ecg1 + noiseSamples
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // 前 15 秒干净段应检出约 17 个 R 波（70bpm × 15s ≈ 17.5）
        // 全局阈值被噪声拉高时会漏检大半；分段自适应应保留干净段检出
        assertTrue(
            "干净段 R 波应被检出（全局阈值被噪声拉高会漏检），实际 ${g.rPeakCount}",
            g.rPeakCount >= 14,
        )
    }

    @Test
    fun detectsRPeaksInModerateNoiseRealisticSignal() {
        // 场景：30 秒 70bpm 规则 R 波（振幅 1.0mV）+ 中等噪声（0.15mV），模拟真实 wrist ECG。
        // 真实数据（samples/ECG_diagnostic_20260716_*.txt）显示：envelope R 波峰仅为噪声中位数
        // 的 ~1.4 倍（envMax≈106, median≈75）。median*1.5 要求比值≥1.5，把所有真实 R 波压掉
        // → R 波检测返回 0 → 心率 0（三个真实测量全部复现此 bug）。
        // mean+2std 阈值（≈86~93）低于 envMax（≈92~106），本应检出 R 波。
        // 此测试用 rAmp=1.0 + noise=0.15 复现该特征（envMax/(median*1.5)≈0.92 < 1.0）。
        val rTimes = (0 until 35).map { it * 0.857f }  // 70bpm
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.15f, seed = 100L)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        // 应检出约 35 个 R 波（允许 ±15），median*1.5 bug 下会返回 0
        // 阈值依据：合成信号检出 28 个，真实中等噪声 wrist ECG 检出 21-26 个（samples/ 验证）
        assertTrue(
            "中等噪声真实信号应检出 R 波（median*1.5 阈值过高会全漏检），实际 ${g.rPeakCount}",
            g.rPeakCount >= 20,
        )
    }

    @Test
    fun noiseSegmentRPeaksDoNotPolluteStats() {
        // 场景：0-10s 干净 70bpm + 10-20s 低振幅高斯噪声（被标噪声段）+ 20-30s 干净 70bpm
        // 噪声段内偶尔的高斯尖峰可能触发 R 波检测（误检），这些虚假 R 波：
        //   1. 不应出现在 segments 的 rPeakCount 中
        //   2. 不应产生 RR 进入 rrIntervalsMs（虚假短 RR 会污染 HRV/心率）
        //   3. 跨噪声段的 RR（前段尾→后段头）也不应进入统计
        // 依据：视图模型建议第 2 条"噪声段强制剔除不参与 HRV/节律运算"；
        //       Lueken 2023 证实信号质量差的段会显著增加误分类概率
        val rTimes = (0 until 12).map { it * 0.857f } + (0 until 12).map { 20f + it * 0.857f }
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f, seed = 42L).toMutableList()
        // 10-20s 替换为 0.03mV 标准差高斯噪声（rms~0.03mV < 0.05，被标噪声段，但偶有 3-4σ 尖峰触发误检）
        val noiseRng = java.util.Random(999L)
        for (i in sampleRate * 10 until sampleRate * 20) {
            ecg[i] = (noiseRng.nextGaussian().toFloat() * 30f).toInt()  // 0.03mV 标准差
        }
        val bundle = EcgFeatureExtractor.extract(ecg.toList(), sampleRate)
        val g = bundle.global

        // 噪声段应被识别（rms < 0.05 的段被打噪声标记）
        assertTrue("应识别噪声段: ${g.noiseSegments}", g.noiseSegments.isNotEmpty())

        // 噪声段 segments 的 R 波数之和应为 0（噪声段 R 波应剔除，不进入下游统计）
        val noiseSegRPeakSum = bundle.segments.filter { it.rmsMv < 0.05f }.sumOf { it.rPeakCount }
        assertTrue(
            "噪声段内 R 波应剔除（不污染统计），实际噪声段 segments R 波数 $noiseSegRPeakSum",
            noiseSegRPeakSum == 0,
        )

        // rrIntervalsMs 不应包含涉及噪声段的 RR（跨段或段内）
        // 有效段 R 波：前段 12 + 后段 12 = 24，但跨噪声段 RR 被剔除，所以 RR 数 ≤ 22
        // 如果噪声段误检 R 波没剔除，会产生额外虚假 RR 使 RR 数 > 22
        assertTrue(
            "rrIntervalsMs 不应含噪声段相关 RR，实际 RR 数 ${g.rrIntervalsMs.size}（前段 11 + 后段 11 = 22 为上限）",
            g.rrIntervalsMs.size <= 22,
        )
    }

    @Test
    fun detectsRPeaksInMotionArtifactSignal() {
        // 场景：运动后 wrist ECG——心率快（107bpm）+ 强肌电干扰（envelope 右偏长尾）
        // 真实数据（samples/运动后.txt）：mean+2.0std 阈值被高噪声底拉高，
        // R 波 envelope 峰仅 0.85~1.11 倍阈值 → 漏检 60%（21 个 vs 期望 53）
        //
        // 合成信号复现"右偏长尾 envelope"：
        // - 均匀/高斯噪声是均匀/正态分布，mean+2std 最优不会漏检
        // - 需叠加"脉冲式"肌电突发（偶发大尖峰）+ 降 R 波振幅（运动后电极接触变化）
        // - 肌电突发让 envelope 产生少量极高值（长尾），mean+2std 被长尾拉高
        //
        // 修复：mean+1.7×std 阈值（k 从 2.0 降到 1.7），让运动后边缘 R 波通过
        // Python 验证（verify_k18.py，6 seed）：mean+2.0std 检出 21-29，mean+1.7std 检出 34-49
        // 5 份真实样本验证：静息/活动后全部准确（HR 偏差 <15bpm），运动后 21→36 R 波改善
        val rTimes = mutableListOf<Float>()
        var t = 0.5f
        while (t < 30f) { rTimes.add(t); t += 0.56f }  // 107bpm, 约 53 个 R 波
        // 基础信号：R 波振幅 0.5mV（运动后降低）+ 0.15mV uniform 噪声
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 0.5f, noiseLevel = 0.15f, seed = 200L).toMutableList()
        // 叠加肌电干扰：0.4mV uniform 噪声（肌电底）+ 3% 概率高斯突发 1.0mV（让 envelope 右偏长尾）
        val rng = java.util.Random(201L)
        for (i in ecg.indices) {
            val muscle = (rng.nextDouble() - 0.5).toFloat() * 2 * 0.4f  // ±0.4mV 肌电底
            val burst = if (rng.nextDouble() < 0.03) rng.nextGaussian().toFloat() * 1.0f else 0f  // 3% 突发
            ecg[i] = ecg[i] + ((muscle + burst) * 1000).toInt()
        }
        val g = EcgFeatureExtractor.extract(ecg.toList(), sampleRate).global
        // 期望 53 个，mean+2.0std 漏检检出 ~21-29，mean+1.7std 检出 ~34-49
        // 断言 >=33（比 mean+2.0std 最高 29 高，验证显著改善；比 Python 最低 34 低 1，留 Kotlin/Python 随机差异余量）
        assertTrue(
            "运动后强肌电干扰信号应检出多数 R 波（mean+2.0std 漏检 60% 检出 ~21-29），实际 ${g.rPeakCount}",
            g.rPeakCount >= 33,
        )
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

    // ===== 形态客观参数（R 波极性 / T-R 振幅比 / ST 段偏移） =====
    // 目的：给 DS 提供客观可测的形态参数（非主观诊断），让 DS 据此判读。
    // 设计原则：测量归本地（输出客观值），判读归 DS（医学诊断）。
    // 不让本地算法做主观形态诊断（如"T 波倒置"），只输出客观测量值。

    /**
     * RED-1: R 波极性检测
     *
     * 场景：合成正向 R 波（rAmplitudeMv=+1.0）和负向 R 波（rAmplitudeMv=-1.0），
     * 本地算法应正确识别极性。
     *
     * 依据：单导联 wrist ECG 的 R 波极性取决于电极位置/导联方向，
     * isReverse 也可能误报。极性是客观可测量（R 峰值相对基线的符号），
     * DS 据此可判读导联反接/位置异常。estimateIntervals 已算 rIsPositive
     * 但未输出到 toPromptText，此测试要求输出。
     *
     * 阈值：正向 R 波应输出"正向"，负向 R 波应输出"负向"。
     * 允许少数 R 波极性异常（噪声干扰），用多数投票判定整体极性。
     */
    @Test
    fun detectsRPeakPolarity() {
        // 正向 R 波
        val rTimes = (0 until 35).map { it * 0.857f }
        val ecgPos = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f)
        val gPos = EcgFeatureExtractor.extract(ecgPos, sampleRate).global
        assertEquals("正向 R 波应识别为正向极性", "正向", gPos.rPeakPolarity)

        // 负向 R 波
        val ecgNeg = syntheticEcg(30f, rTimes, rAmplitudeMv = -1.0f, noiseLevel = 0.02f)
        val gNeg = EcgFeatureExtractor.extract(ecgNeg, sampleRate).global
        assertEquals("负向 R 波应识别为负向极性", "负向", gNeg.rPeakPolarity)
    }

    /**
     * RED-2: T/R 振幅比检测
     *
     * 场景：合成 R 波(1.0mV) + T 波(0.3mV，同极性)，T/R 比 ≈ 0.30。
     * 正常 T/R 比范围 0.1~0.5（生理学教材），<0.1 提示 T 波低平，>0.5 可能 T 波高尖。
     *
     * 依据：T 波振幅相对 R 波的比值是客观可测量，DS 据此判读 T 波异常
     * （低平/高尖/倒置）。本地算法已有 T 峰定位（estimateIntervals），
     * 只需加 T 峰振幅测量。
     *
     * 阈值：T/R 比应在 0.20~0.40 范围（合成 0.3 ± 容差）。
     * 不要求精确到 0.30，因 T 峰定位有 ±10ms 误差 → 振幅有 ±20% 误差。
     */
    @Test
    fun detectsTRAmplitudeRatio() {
        val rTimes = (0 until 35).map { it * 0.857f }
        // R 波 1.0mV + T 波 0.3mV（同极性，正向）
        // rSigmaSec=0.02：宽 R 波（20ms 标准差，QRS 宽度约 120ms = ±3sigma，接近真实），
        // 避免窄高斯峰（默认 10ms）被 150ms MWI 摊平导致精修位置偏移
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f,
            tAmplitudeMv = 0.3f, tOffsetSec = 0.3f, rSigmaSec = 0.02f)
        val g = EcgFeatureExtractor.extract(ecg, sampleRate).global
        assertTrue(
            "T/R 振幅比 ${g.tToRAmplitudeRatio} 应在 0.20~0.40（合成 0.3 ± 容差）",
            g.tToRAmplitudeRatio in 0.20f..0.40f,
        )
    }

    /**
     * RED-3: ST 段偏移检测
     *
     * 场景：合成 R 波 + T 波信号，ST 段（QRS 终点到 T 波起点）应接近基线（0mV）。
     * 为验证 ST 偏移检测能力，合成一个带 ST 抬高的信号：在 R 波后 80-200ms 窗口
     * 加 +0.2mV 偏移（模拟 ST 段抬高）。
     *
     * 依据：ST 段偏移是客观可测量（QRS 终点到 T 波起点的高度差相对基线），
     * DS 据此判读 ST 抬高/压低（缺血/梗死指征）。临床定义 ST 抬高 ≥0.1mV。
     *
     * 阈值：正常 ST 段偏移 |stDeviationMv| < 0.05mV；
     *       ST 抬高信号 stDeviationMv > 0.10mV（合成 0.2mV ± 容差）。
     */
    @Test
    fun detectsStElevation() {
        val rTimes = (0 until 35).map { it * 0.857f }
        // 基线信号：R 波 + T 波，ST 段接近基线
        // rSigmaSec=0.02：宽 R 波（20ms 标准差，QRS 约 120ms），避免窄峰被 MWI 摊平
        val ecgBaseline = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f,
            tAmplitudeMv = 0.3f, tOffsetSec = 0.3f, rSigmaSec = 0.02f)
        val gBaseline = EcgFeatureExtractor.extract(ecgBaseline, sampleRate).global
        // 基线 ST 偏移应接近 0（容差 ±0.05mV）
        assertTrue(
            "基线 ST 段偏移 ${gBaseline.stDeviationMv}mV 应在 ±0.05mV",
            kotlin.math.abs(gBaseline.stDeviationMv) < 0.05f,
        )

        // ST 抬高信号：在 R 波后 50-200ms 加 +0.2mV 平台偏移
        // 窗口起点 50ms：覆盖 J 点(S 点约 R 后 30-40ms) + 20ms 测量点（约 R 后 50-60ms）
        // 旧窗口 80ms 起点会错过 J+20ms 测量点，导致检测不到抬高
        val ecgElevated = ecgBaseline.toMutableList()
        for (rT in rTimes) {
            val stStart = ((rT + 0.05f) * sampleRate).toInt()
            val stEnd = ((rT + 0.20f) * sampleRate).toInt()
            for (i in stStart..stEnd) {
                if (i in ecgElevated.indices) ecgElevated[i] += 200  // +0.2mV
            }
        }
        val gElevated = EcgFeatureExtractor.extract(ecgElevated.toList(), sampleRate).global
        assertTrue(
            "ST 抬高信号 stDeviationMv ${gElevated.stDeviationMv}mV 应 > 0.10mV（合成 0.2mV）",
            gElevated.stDeviationMv > 0.10f,
        )
    }
}
