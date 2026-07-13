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
    else -> label
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
