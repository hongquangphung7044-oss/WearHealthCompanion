package com.wearhealth.companion.model

/**
 * ECG 分析结果（对应 HeartVoice API 响应）
 */
data class EcgAnalysisResult(
    val isAbnormal: Boolean,
    val signalQuality: Double,   // sqGrade, 0~1, 建议 ≥0.7 才信任结果
    val diagnosis: List<String>,  // 诊断标签列表，如 ["SN"]=窦性心律, ["AF"]=房颤
    val avgHeartRate: Int,        // 平均心率
    val minHeartRate: Int = 0,    // 最低心率（本地 R-R 间期计算，API 不返回）
    val maxHeartRate: Int = 0,    // 最高心率（本地 R-R 间期计算，API 不返回）
    val avgQrs: Int,              // QRS 宽度 ms
    val prInterval: Int,          // PR 间期 ms
    val avgQt: Int,               // QT 间期 ms
    val avgQtc: Int,              // 校正 QT 间期 ms
    val pacCount: Int,            // 房性早搏次数
    val pvcCount: Int,            // 室性早搏次数
    val rawData: String,          // 原始 API 响应（调试用）
    val ecgSamples: List<Int> = emptyList(),  // ECG 波形数据（降采样后，用于结果显示）
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
    "VPB" -> "室性早搏"
    "APB" -> "房性早搏"
    "BBB" -> "束支传导阻滞"
    "AVB" -> "房室传导阻滞"
    "ST" -> "ST 段异常"
    "QT" -> "QT 间期异常"
    "WPW" -> "预激综合征"
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
    "VPB" -> false       // 偶发室早通常无害
    "APB" -> false       // 偶发房早通常无害
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
    if (minHeartRate > 0 && maxHeartRate > 0) {
        add(EcgParamInfo(
            "心率范围", "$minHeartRate ~ $maxHeartRate bpm", "60-100",
            "测量期间最低到最高心率。波动大可能提示心律不齐"
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
    object Connecting : EcgCollectionState()                                    // 预热中 / 等待电极接触
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
 * 算法：自适应阈值峰值检测
 * 1. 去基线（减均值）
 * 2. 计算信号包络（滑动最大值）
 * 3. 用包络的 60% 作为动态阈值检测 R 波峰值
 * 4. 相邻 R 峰间隔 = R-R 间期 → 瞬时心率 = 60000 / RR_ms
 * 5. 取所有瞬时心率的 min/max
 *
 * @param ecgData ECG 数据（mV×1000 整数）
 * @param sampleRateHz 采样率（Samsung SDK = 500Hz）
 * @return Pair(minHeartRate, maxHeartRate)，检测失败返回 (0,0)
 */
fun computeMinMaxHeartRate(ecgData: List<Int>, sampleRateHz: Int): Pair<Int, Int> {
    if (ecgData.size < sampleRateHz * 5) return 0 to 0  // 至少 5 秒数据

    // 1. 去基线
    val mean = ecgData.average()
    val centered = ecgData.map { (it - mean) }

    // 2. 滑动最大值包络（窗口 ~0.5 秒）
    val windowSize = sampleRateHz / 2
    val envelope = DoubleArray(centered.size)
    for (i in centered.indices) {
        val from = maxOf(0, i - windowSize / 2)
        val to = minOf(centered.size, i + windowSize / 2)
        var mx = 0.0
        for (j in from until to) if (centered[j] > mx) mx = centered[j]
        envelope[i] = mx
    }

    // 3. 检测 R 峰：信号 > 动态阈值 且 为局部最大值
    val rPeaks = mutableListOf<Int>()
    val refractoryMs = 250  // 不应期 250ms（最多 240bpm）
    val refractorySamples = (refractoryMs * sampleRateHz / 1000)
    var lastPeakIdx = -refractorySamples * 2

    for (i in centered.indices) {
        val threshold = envelope[i] * 0.6
        if (centered[i] > threshold && threshold > 0) {
            // 检查局部最大值（±5 样本）
            val lo = maxOf(0, i - 5)
            val hi = minOf(centered.size, i + 6)
            var isLocalMax = true
            for (j in lo until hi) {
                if (j != i && centered[j] > centered[i]) { isLocalMax = false; break }
            }
            if (isLocalMax && (i - lastPeakIdx) > refractorySamples) {
                rPeaks.add(i)
                lastPeakIdx = i
            }
        }
    }

    if (rPeaks.size < 3) return 0 to 0

    // 4. 计算 R-R 间期 → 瞬时心率
    val instantHrs = mutableListOf<Int>()
    for (i in 1 until rPeaks.size) {
        val rrSamples = rPeaks[i] - rPeaks[i - 1]
        val rrMs = rrSamples * 1000.0 / sampleRateHz
        if (rrMs in 300.0..2000.0) {  // 30~200 bpm 范围合理
            val hr = (60000.0 / rrMs).toInt()
            if (hr in 30..200) instantHrs.add(hr)
        }
    }

    if (instantHrs.isEmpty()) return 0 to 0
    // 去掉最高最低各 1 个异常值（可选，更稳健）
    return instantHrs.min() to instantHrs.max()
}
