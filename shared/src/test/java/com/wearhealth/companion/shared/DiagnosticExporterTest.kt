package com.wearhealth.companion.shared

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * DiagnosticExporter 单元测试（纯 JVM，由 CI `:shared:testReleaseUnitTest` 运行）。
 *
 * 验证"导出算法诊断包"生成的纯文本包含三段对照数据：
 * 1. 原始 ECG 数据（mV×1000 整数，全量不降采样）
 * 2. 算法提取后的结构化特征文本（toPromptText 重建）
 * 3. AI 给出的解读（aiReport JSON）
 *
 * 用途：用户在手机端导出后贴给助手，助手据此精确定位是原始数据/本地算法/AI 哪一步出错。
 */
class DiagnosticExporterTest {

    private val sampleRate = 500

    /**
     * 合成单导联 ECG 信号（高斯形态 R 波 + 微小噪声），用于测试本地算法能检出 R 波。
     * 复刻自 EcgFeatureExtractorTest.syntheticEcg（那里是 private，这里独立实现）。
     */
    private fun syntheticEcg(
        durationSec: Float,
        rPeakTimes: List<Float>,
        rAmplitudeMv: Float = 1.0f,
        noiseLevel: Float = 0.02f,
        seed: Long = 42L,
    ): List<Int> {
        val totalSamples = (durationSec * sampleRate).toInt()
        val data = IntArray(totalSamples)
        val sigma = 0.01f  // R 波宽度 10ms 标准差
        val sigmaSq2 = 2 * sigma * sigma
        val rng = java.util.Random(seed)
        for (i in 0 until totalSamples) {
            val t = i.toFloat() / sampleRate
            var v = 0f
            for (rT in rPeakTimes) {
                val dt = t - rT
                v += rAmplitudeMv * kotlin.math.exp(-(dt * dt) / sigmaSq2).toFloat()
            }
            v += (rng.nextDouble() - 0.5).toFloat() * 2 * noiseLevel
            data[i] = (v * 1000).toInt()
        }
        return data.toList()
    }

    private fun makeTransfer(
        ecgData: List<Int> = (0 until sampleRate * 30).map { (Math.sin(it * 0.1) * 500).toInt() },
        aiReport: String = """{"综合解读":"测试解读","平均心率":72}""",
        analysisMethod: String = "ds_flash_fast",
        ppgReferenceHr: Int = 75,
        avgHeartRate: Int = 72,
        diagnosis: List<String> = listOf("窦性心律"),
        avgQrs: Int = 95,
        prInterval: Int = 160,
    ): EcgMeasurementTransfer = EcgMeasurementTransfer(
        timestamp = 1720000000000L,
        diagnosis = diagnosis,
        possibleDiagnoses = emptyList(),
        isReverse = false,
        avgHeartRate = avgHeartRate,
        minHeartRate = 68,
        maxHeartRate = 78,
        signalQuality = 0.85,
        isAbnormal = false,
        avgQrs = avgQrs,
        avgP = 0,
        prInterval = prInterval,
        avgQt = 380,
        avgQtc = 417,
        pacCount = 0,
        pvcCount = 0,
        rawEcgData = ecgData,
        downsampledEcg = emptyList(),
        sampleRate = sampleRate,
        analysisMethod = analysisMethod,
        aiReport = aiReport,
        tavilyStatus = "未触发（仅 Max 档联网检索）",
        ppgReferenceHr = ppgReferenceHr,
    )

    @Test
    fun exportContainsAllThreeSections() {
        val ecg = (0 until sampleRate * 30).map { (Math.sin(it * 0.1) * 500).toInt() }
        val transfer = makeTransfer(ecgData = ecg)
        val text = DiagnosticExporter.buildText(transfer)

        // 三段标题都应存在
        assertTrue("应包含 [原始 ECG 数据] 段", text.contains("[原始 ECG 数据]"))
        assertTrue("应包含 [算法提取后的结构化特征] 段", text.contains("[算法提取后的结构化特征]"))
        assertTrue("应包含 [AI 解读] 段", text.contains("[AI 解读]"))
    }

    @Test
    fun exportContainsFullRawEcgDataNoDownsample() {
        // 15000 个点必须全部出现，不能降采样
        val ecg = (0 until sampleRate * 30).map { it * 2 - 15000 }  // 每个值唯一可识别
        val transfer = makeTransfer(ecgData = ecg)
        val text = DiagnosticExporter.buildText(transfer)

        // 原始数据段应包含全部 15000 个点（以空格/换行分隔）
        // 注意：注释行里可能含纯数字（如"采样率: 500 Hz"），所以用 >= 而非 == 判断下限
        val rawSection = text.substringAfter("[原始 ECG 数据]").substringBefore("[算法提取后的结构化特征]")
        val numbers = rawSection.split(Regex("\\s+")).filter { it.isNotBlank() && it.matches(Regex("-?\\d+")) }
        assertTrue(
            "原始数据应全量导出不降采样，至少 ${ecg.size} 个点，实际 $numbers",
            numbers.size >= ecg.size,
        )
        // 第一个和最后一个点都应在（验证没截断）
        assertTrue("应包含首点 ${ecg.first()}", rawSection.contains(ecg.first().toString()))
        assertTrue("应包含末点 ${ecg.last()}", rawSection.contains(ecg.last().toString()))
        // 每个原始值都应出现（用唯一值序列验证不丢点）
        for (v in ecg) {
            assertTrue("原始点 $v 未出现在导出中（可能降采样/截断）", rawSection.contains(v.toString()))
        }
    }

    @Test
    fun exportContainsRecomputedFeatureText() {
        val ecg = (0 until sampleRate * 30).map { (Math.sin(it * 0.1) * 500).toInt() }
        val transfer = makeTransfer(ecgData = ecg)
        val text = DiagnosticExporter.buildText(transfer)

        // 算法特征段应包含 toPromptText 的关键标记
        val featureSection = text.substringAfter("[算法提取后的结构化特征]").substringBefore("[AI 解读]")
        assertTrue("特征段应包含 [全局指标]", featureSection.contains("[全局指标]"))
        assertTrue("特征段应包含采样率", featureSection.contains("采样率:${sampleRate}Hz"))
        assertTrue("特征段应包含 [逐秒分段]", featureSection.contains("[逐秒分段]"))
        // 应包含 PPG 参考心率（transfer.ppgReferenceHr=75）
        assertTrue("特征段应包含 PPG 参考心率 75", featureSection.contains("PPG绿光参考心率:75bpm"))
    }

    @Test
    fun exportContainsAiReportJson() {
        val aiJson = """{"综合解读":"测试解读","平均心率":72}"""
        val transfer = makeTransfer(aiReport = aiJson)
        val text = DiagnosticExporter.buildText(transfer)

        val aiSection = text.substringAfter("[AI 解读]")
        assertTrue("AI 解读段应包含原始 JSON", aiSection.contains(aiJson))
    }

    @Test
    fun exportContainsMetadataHeader() {
        val transfer = makeTransfer()
        val text = DiagnosticExporter.buildText(transfer)

        // 元信息头：时间戳、采样率、点数、分析方法
        assertTrue("应包含测量时间戳", text.contains("测量时间戳:1720000000000"))
        assertTrue("应包含采样率", text.contains("采样率:$sampleRate Hz"))
        assertTrue("应包含数据点数", text.contains("数据点数:${sampleRate * 30}"))
        assertTrue("应包含分析方法", text.contains("分析方法:ds_flash_fast"))
        // 算法版本号应自动注入到元信息头（引用 EcgFeatureExtractor.ALGORITHM_VERSION）
        assertTrue(
            "元信息头应包含算法版本号",
            text.contains("算法版本:${EcgFeatureExtractor.ALGORITHM_VERSION}"),
        )
        // 全局指标段也应带版本号（toPromptText 输出）
        val featureSection = text.substringAfter("[算法提取后的结构化特征]")
        assertTrue(
            "特征段 [全局指标] 应包含算法版本号",
            featureSection.contains("算法版本:${EcgFeatureExtractor.ALGORITHM_VERSION}"),
        )
    }

    @Test
    fun exportNotesProfileMissing() {
        // transfer 不含用户 profile，导出应明确标注（年龄性别未知）
        val transfer = makeTransfer()
        val text = DiagnosticExporter.buildText(transfer)

        assertTrue(
            "应标注 profile 未存（手机端重建特征时年龄性别未知）",
            text.contains("用户属性") && text.contains("未知"),
        )
    }

    @Test
    fun exportHandlesEmptyAiReport() {
        // 非 DS 分析方法时 aiReport 可能为空
        val transfer = makeTransfer(aiReport = "", analysisMethod = "heartvoice")
        val text = DiagnosticExporter.buildText(transfer)

        assertTrue("应包含 [AI 解读] 段（即使为空）", text.contains("[AI 解读]"))
        val aiSection = text.substringAfter("[AI 解读]")
        assertTrue(
            "空 AI 报告应标注'本次测量未使用 AI 分析'",
            aiSection.contains("未使用 AI 分析") || aiSection.contains("无 AI 解读"),
        )
    }

    // ===== HeartVoice 路径导出修复 =====
    // Bug：旧实现不管 analysisMethod 都重建本地特征，HeartVoice 路径本地算法在真实
    // wrist ECG 上可能返回 0 个 R 波 → 导出 [全局指标] 全 0，但 HeartVoice API 实际
    // 返回了有效数据（avgHeartRate/diagnosis 等）。修复：HeartVoice 路径加专门的
    // [HeartVoice API 返回] 段，输出 transfer 已有的统计字段（API 解析后填入的）。

    @Test
    fun heartVoiceExportContainsApiReturnSection() {
        // HeartVoice 路径：analysisMethod="heartvoice"，应输出 API 返回段
        val transfer = makeTransfer(
            analysisMethod = "heartvoice",
            aiReport = "",
            avgHeartRate = 72,
            diagnosis = listOf("SN"),
            avgQrs = 95,
            prInterval = 160,
        )
        val text = DiagnosticExporter.buildText(transfer)
        assertTrue(
            "HeartVoice 路径应包含 [HeartVoice API 返回] 段",
            text.contains("[HeartVoice API 返回]"),
        )
        val hvSection = text.substringAfter("[HeartVoice API 返回]")
        assertTrue("HeartVoice 段应包含平均心率 72", hvSection.contains("平均心率:72"))
        assertTrue("HeartVoice 段应包含诊断 SN", hvSection.contains("SN"))
        assertTrue("HeartVoice 段应包含 QRS 95", hvSection.contains("QRS宽度:95"))
        assertTrue("HeartVoice 段应包含 PR 160", hvSection.contains("PR间期:160"))
    }

    @Test
    fun dsPathDoesNotShowHeartVoiceSection() {
        // DS 路径不应出现 HeartVoice 段（避免误导）
        val transfer = makeTransfer(analysisMethod = "ds_flash_balanced")
        val text = DiagnosticExporter.buildText(transfer)
        assertFalse(
            "DS 路径不应包含 [HeartVoice API 返回] 段",
            text.contains("[HeartVoice API 返回]"),
        )
    }

    @Test
    fun exportContainsLocalAlgorithmDetailsForDiff() {
        // 导出应包含本地算法的 R 波位置/RR 间期/Poincaré 等详细指标，
        // 方便与 HeartVoice API 返回对照，定位差异点。
        // 用合成 ECG（高斯 R 波）而非默认 sin 波，确保本地算法能检出 R 波 →
        // R 波位置/RR 间期序列才有内容输出（sin 波是高频信号非 ECG 形态，检不出 R 波）。
        val rTimes = (0 until 35).map { it * 0.857f }  // 30s 70bpm
        val ecg = syntheticEcg(30f, rTimes, rAmplitudeMv = 1.0f, noiseLevel = 0.02f)
        val transfer = makeTransfer(ecgData = ecg)
        val text = DiagnosticExporter.buildText(transfer)
        val featureSection = text.substringAfter("[算法提取后的结构化特征]")
        // R 波位置数组（索引序列）
        assertTrue(
            "特征段应包含 R 波位置索引序列",
            featureSection.contains("R波位置(样本索引)"),
        )
        // RR 间期序列
        assertTrue("特征段应包含 RR 间期序列", featureSection.contains("RR间期序列"))
        // Poincaré SD1/SD2
        assertTrue("特征段应包含 Poincaré SD1/SD2", featureSection.contains("Poincaré") || featureSection.contains("SD1"))
    }

    @Test
    fun exportContainsPerSegmentRmsCurve() {
        // 逐秒 RMS 曲线帮助识别噪声段分布
        val transfer = makeTransfer()
        val text = DiagnosticExporter.buildText(transfer)
        val featureSection = text.substringAfter("[算法提取后的结构化特征]")
        assertTrue(
            "特征段应包含逐秒 RMS 曲线",
            featureSection.contains("逐秒RMS"),
        )
    }
}
