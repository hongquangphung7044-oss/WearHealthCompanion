package com.wearhealth.companion.model

/**
 * ECG 分析结果（对应 HeartVoice API 响应）
 */
data class EcgAnalysisResult(
    val isAbnormal: Boolean,
    val signalQuality: Double,   // sqGrade, 0~1, 建议 ≥0.7 才信任结果
    val diagnosis: List<String>,  // 诊断标签列表，如 ["SN"]=窦性心律, ["AF"]=房颤
    val avgHeartRate: Int,        // 平均心率
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
            "心率", "$avgHeartRate bpm", "60-100",
            "每分钟心跳次数。运动/紧张会升高，睡眠时降低"
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
