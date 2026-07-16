package com.wearhealth.companion.shared

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    private fun makeTransfer(
        ecgData: List<Int> = (0 until sampleRate * 30).map { (Math.sin(it * 0.1) * 500).toInt() },
        aiReport: String = """{"综合解读":"测试解读","平均心率":72}""",
        analysisMethod: String = "ds_flash_fast",
        ppgReferenceHr: Int = 75,
    ): EcgMeasurementTransfer = EcgMeasurementTransfer(
        timestamp = 1720000000000L,
        diagnosis = listOf("窦性心律"),
        possibleDiagnoses = emptyList(),
        isReverse = false,
        avgHeartRate = 72,
        minHeartRate = 68,
        maxHeartRate = 78,
        signalQuality = 0.85,
        isAbnormal = false,
        avgQrs = 95,
        avgP = 0,
        prInterval = 160,
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
}
