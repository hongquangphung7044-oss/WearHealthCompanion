package com.wearhealth.companion.model

/**
 * ECG 分析结果（对应 HeartVoice API 响应）
 */
data class EcgAnalysisResult(
    val isAbnormal: Boolean,
    val signalQuality: Double,   // sqGrade, 0~1, 建议 ≥0.7 才信任结果
    val diagnosis: List<String>,  // 确定诊断标签
    val possibleDiagnoses: List<String> = emptyList(), // API 提示的可能诊断
    val isReverse: Boolean = false, // API 检测到导联可能拿反
    val avgHeartRate: Int,        // 平均心率
    val minHeartRate: Int = 0,    // 最低心率（本地 R-R 间期计算，API 不返回）
    val maxHeartRate: Int = 0,    // 最高心率（本地 R-R 间期计算，API 不返回）
    val avgQrs: Int,              // QRS 宽度 ms
    val avgP: Int = 0,             // 平均 P 波宽度 ms
    val prInterval: Int,          // PR 间期 ms
    val avgQt: Int,               // QT 间期 ms
    val avgQtc: Int,              // 校正 QT 间期 ms
    val pacCount: Int,            // 房性早搏次数
    val pvcCount: Int,            // 室性早搏次数
    val rawData: String,          // 原始 API 响应（调试用）
    val ecgSamples: List<Int> = emptyList(),  // ECG 波形数据（降采样后，用于结果显示）
    val analysisMethod: String = "heartvoice", // 分析方式：heartvoice / ds_*
    val aiReport: String = "",                 // DeepSeek JSON 报告（仅 DS 方式有值）
)

/**
 * 诊断标签 → 中文说明
 */
fun diagnosisLabelToText(label: String): String = when (label) {
    "SN" -> "窦性心律（正常）"
    "SNT" -> "窦性心动过速"
    "SNB" -> "窦性心动过缓"
    "AF" -> "心房颤动"
    "AFL" -> "心房扑动"
    "VPB", "PVC" -> "室性早搏"
    "APB", "PAC" -> "房性早搏"
    "BBB" -> "束支传导阻滞"
    "AVB" -> "房室传导阻滞"
    "ST" -> "ST 段异常"
    "QT" -> "QT 间期异常"
    "WPW" -> "预激综合征"
    "ARR" -> "疑似心律不齐"
    else -> label
}

/**
 * 诊断标签 → 是否严重（true = 需要就医）
 */
fun isDiagnosisSerious(label: String): Boolean = when (label) {
    "SN" -> false        // 窦性心律，正常
    "SNT" -> false       // 心动过速，可能是暂时的
    "SNB" -> false       // 心动过缓，运动员常见
    "AF" -> true         // 房颤，需就医
    "AFL" -> true        // 房扑，需就医
    "VPB", "PVC" -> false       // 偶发室早通常无害
    "APB", "PAC" -> false       // 偶发房早通常无害
    "BBB" -> true        // 传导阻滞，需进一步检查
    "AVB" -> true        // 房室传导阻滞，需就医
    "ST" -> true         // ST 异常，需就医
    "QT" -> true         // QT 异常，需就医
    "WPW" -> true        // 预激综合征，需就医确认
    else -> false
}

/**
 * 诊断结果 → 通俗解读（给普通用户看的总结）
 */
fun diagnosisSummary(diagnosis: List<String>): String {
    if (diagnosis.isEmpty()) return "未检测到异常"
    val hasNormal = diagnosis.any { it == "SN" }
    val seriousList = diagnosis.filter { isDiagnosisSerious(it) }
    return when {
        hasNormal && seriousList.isEmpty() ->
            "心律正常（窦性心律），这是健康的心跳节奏"
        hasNormal && seriousList.isNotEmpty() ->
            "基础心律正常，但检测到 ${seriousList.size} 项需关注的异常，建议咨询医生"
        seriousList.isNotEmpty() ->
            "检测到 ${seriousList.size} 项异常，建议尽快咨询医生确认"
        else ->
            "检测到轻微异常，一般无需担心，可定期复查"
    }
}

/**
 * ECG 参数 → 通俗说明
 */
data class EcgParamInfo(val label: String, val value: String, val normal: String, val desc: String)

fun EcgAnalysisResult.toParamInfos(): List<EcgParamInfo> = buildList {
    if (avgHeartRate > 0) {
        add(EcgParamInfo(
            "平均心率", "$avgHeartRate bpm", "60-100",
            "30 秒测量期间的平均每分钟心跳次数"
        ))
    }
    add(EcgParamInfo(
        "最低心率",
        if (minHeartRate > 0) "$minHeartRate bpm" else "暂无可靠估算",
        "本地趋势参考",
        "由单导联波形的有效 R-R 间期本地估算；无法稳定识别时不隐藏项目，也不填入猜测值",
    ))
    add(EcgParamInfo(
        "最高心率",
        if (maxHeartRate > 0) "$maxHeartRate bpm" else "暂无可靠估算",
        "本地趋势参考",
        "由单导联波形的有效 R-R 间期本地估算；并非 HeartVoice API 直接返回",
    ))
    if (avgP > 0) {
        add(EcgParamInfo(
            "平均 P 波宽度", "$avgP ms", "API 输出",
            "单导联 API 估算的平均 P 波宽度"
        ))
    }
    if (avgQrs > 0) {
        add(EcgParamInfo(
            "QRS 宽度", "$avgQrs ms", "80-120",
            "心室收缩时间。过宽可能提示心室传导问题"
        ))
    }
    if (prInterval > 0) {
        add(EcgParamInfo(
            "PR 间期", "$prInterval ms", "120-200",
            "心房到心室的传导时间。WPW 综合征时会偏短"
        ))
    }
    if (avgQt > 0) {
        add(EcgParamInfo(
            "QT 间期", "$avgQt ms", "随心率变化",
            "心室收缩+恢复的总时间"
        ))
    }
    if (avgQtc > 0) {
        add(EcgParamInfo(
            "QTc", "$avgQtc ms", "男<450 / 女<460",
            "校正心率后的 QT。过长可能有心律风险"
        ))
    }
}

/**
 * ECG 采集状态
 */
sealed class EcgCollectionState {
    object Idle : EcgCollectionState()
    object Connecting : EcgCollectionState()                                    // 连接传感器 / 等待电极接触
    data class Preheating(val countdownSec: Int) : EcgCollectionState()         // 电极已接触，预热激活中（信号稳定期）
    data class Collecting(val samplesCollected: Int, val countdownSec: Int) : EcgCollectionState()
    object Analyzing : EcgCollectionState()
    data class Done(val result: EcgAnalysisResult) : EcgCollectionState()
    data class Error(val message: String) : EcgCollectionState()
}

/**
 * 检测诊断标签是否与实测参数矛盾
 *
 * WPW 典型三联征：PR<120ms + QRS>120ms + delta 波
 * 若诊断含 WPW 但 PR≥120 或 QRS≤120，则与数据矛盾 → 提示可能误判
 */
fun hasDiagnosisConflict(result: EcgAnalysisResult): String? {
    if (result.diagnosis.contains("WPW")) {
        // WPW 需要 PR<120 且 QRS>120，否则矛盾
        val prNormal = result.prInterval in 120..200
        val qrsNormal = result.avgQrs in 80..120
        if (prNormal && qrsNormal && result.prInterval > 0 && result.avgQrs > 0) {
            return "WPW 诊断与你的参数矛盾（PR=${result.prInterval}ms 正常，QRS=${result.avgQrs}ms 正常），" +
                    "典型 WPW 应 PR<120ms 且 QRS>120ms，可能是 AI 误判，请咨询医生确认"
        }
    }
    return null
}

/**
 * 本地 R 波检测：从 ECG 数据计算最低/最高心率
 *
 * 算法改进（解决之前 31~194 的误检问题）：
 * 1. 带通滤波：减去移动平均去基线漂移，再移动平均去高频噪声
 * 2. 平滑：3 点移动平均减少毛刺
 * 3. 自适应阈值 = 信号 RMS × 2 + 绝对最小值
 * 4. R 峰形态验证：检查峰宽（太宽=噪声）+ 斜率（太缓=基线漂移）
 * 5. 异常值剔除：R-R 间期偏离中位数 ±30% 的丢弃
 * 6. 最终 min/max 从有效心率中取
 *
 * @param ecgData ECG 数据（mV×1000 整数）
 * @param sampleRateHz 采样率（Samsung SDK = 500Hz）
 * @return Pair(minHeartRate, maxHeartRate)，检测失败返回 (0,0)
 */
fun computeMinMaxHeartRate(ecgData: List<Int>, sampleRateHz: Int): Pair<Int, Int> {
    if (ecgData.size < sampleRateHz * 5) return 0 to 0  // 至少 5 秒数据

    // 1. 带通滤波：去基线漂移（高通）+ 去高频噪声（低通）
    val baselineWindow = sampleRateHz  // 1 秒窗口去基线
    val baseline = DoubleArray(ecgData.size)
    for (i in ecgData.indices) {
        val from = maxOf(0, i - baselineWindow / 2)
        val to = minOf(ecgData.size, i + baselineWindow / 2)
        var sum = 0.0
        for (j in from until to) sum += ecgData[j]
        baseline[i] = sum / (to - from)
    }
    val highPassed = ecgData.mapIndexed { i, v -> v - baseline[i] }

    // 2. 低通平滑：5 点移动平均去高频噪声
    val smoothWin = 5
    val filtered = DoubleArray(highPassed.size)
    for (i in highPassed.indices) {
        val from = maxOf(0, i - smoothWin / 2)
        val to = minOf(highPassed.size, i + smoothWin / 2 + 1)
        var sum = 0.0
        for (j in from until to) sum += highPassed[j]
        filtered[i] = sum / (to - from)
    }

    // 3. 自动选择主峰极性。单导联佩戴、电极方向和设备坐标可能让 R 峰向上或向下；
    // isReverse 也可能误报，因此本地显示算法不假定正峰，更不能据此拒绝 ECG。
    val positivePeak = filtered.maxOrNull() ?: 0.0
    val negativePeak = kotlin.math.abs(filtered.minOrNull() ?: 0.0)
    val polarity = if (negativePeak > positivePeak) -1.0 else 1.0
    val oriented = DoubleArray(filtered.size) { index -> filtered[index] * polarity }

    // 4. 计算信号 RMS 作为自适应阈值
    val rms = Math.sqrt(oriented.map { it * it }.average())
    val threshold = rms * 2.0  // R 波幅度通常是噪声的 2-5 倍
    if (threshold < 50.0) return 0 to 0  // 信号太弱

    // 5. 检测 R 峰：阈值 + 局部最大 + 形态验证
    val rPeaks = mutableListOf<Int>()
    val refractorySamples = sampleRateHz / 3  // 不应期 333ms（最多 180bpm）
    var lastPeakIdx = -refractorySamples * 2
    val checkRange = sampleRateHz / 50  // ±10ms 局部最大检查

    for (i in oriented.indices) {
        if (oriented[i] < threshold) continue
        // 检查局部最大值
        val lo = maxOf(0, i - checkRange)
        val hi = minOf(oriented.size, i + checkRange + 1)
        var isLocalMax = true
        for (j in lo until hi) {
            if (j != i && oriented[j] > oriented[i]) { isLocalMax = false; break }
        }
        if (!isLocalMax) continue
        // 形态验证：峰值附近的斜率要够大（R 波是尖窄峰）
        val slopeIdx = maxOf(0, i - sampleRateHz / 20)  // 50ms 前
        val slope = (oriented[i] - oriented[slopeIdx])
        if (slope < threshold * 0.5) continue  // 斜率不够，可能是缓波
        // 不应期检查
        if ((i - lastPeakIdx) < refractorySamples) continue

        rPeaks.add(i)
        lastPeakIdx = i
    }

    if (rPeaks.size < 5) return 0 to 0  // 至少 5 个心跳

    // 6. 计算 R-R 间期 → 瞬时心率，剔除异常值
    val instantHrs = mutableListOf<Int>()
    for (i in 1 until rPeaks.size) {
        val rrMs = (rPeaks[i] - rPeaks[i - 1]) * 1000.0 / sampleRateHz
        if (rrMs in 400.0..1500.0) {  // 40~150 bpm 合理范围
            val hr = (60000.0 / rrMs).toInt()
            if (hr in 40..150) instantHrs.add(hr)
        }
    }

    if (instantHrs.size < 3) return 0 to 0

    // 剔除异常值：以中位数为中心，±30% 范围外的丢弃
    val sorted = instantHrs.sorted()
    val median = sorted[sorted.size / 2]
    val filtered2 = instantHrs.filter { hr ->
        val dev = kotlin.math.abs(hr - median).toDouble() / median
        dev <= 0.3
    }

    if (filtered2.isEmpty()) return 0 to 0
    // 最低和最高心率
    return filtered2.min() to filtered2.max()
}

/**
 * 本地信号质量预检（不发 API，节省用量）
 *
 * 检查项：
 * 1. 数据量是否足够（至少 10 秒）
 * 2. 信号幅度是否合理（不能太平，也不能全是噪声）
 * 3. 能否检测到至少 8 个心跳
 *
 * @return null=通过，非 null=失败原因
 */
fun localSignalQualityCheck(ecgData: List<Int>, sampleRateHz: Int): String? {
    // 预检只做最基本的检查：数据量 + 信号幅度
    // 不做心跳检测——那是 API 的工作，本地算法不可靠
    if (ecgData.size < sampleRateHz * 10) {
        return "采集数据不足（仅 ${ecgData.size / sampleRateHz} 秒），需要至少 10 秒有效数据"
    }

    // 检查信号幅度：太小说明电极完全没接触
    val mean = ecgData.average()
    val variance = ecgData.map { (it - mean) * (it - mean) }.average()
    val rms = Math.sqrt(variance)
    if (rms < 10.0) {
        return "信号太弱，可能是电极接触不良。请确保手表戴紧、手指完全覆盖上方按键"
    }

    // 通过——心跳检测、波形质量等交给 API 的 sqGrade 判断
    return null
}
