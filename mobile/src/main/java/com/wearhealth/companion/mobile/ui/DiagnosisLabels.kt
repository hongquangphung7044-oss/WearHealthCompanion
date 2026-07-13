package com.wearhealth.companion.mobile.ui

/**
 * 诊断标签中文化与通俗解读工具
 *
 * 复制手表端 (com.wearhealth.companion.model) 的诊断标签逻辑，
 * 供手机端 UI 与 PDF 导出共用，避免跨模块依赖手表端代码。
 */

/** 诊断标签 → 中文说明 */
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

/** 诊断标签 → 是否严重（true = 需要就医） */
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

/** 将诊断列表转为中文顿号分隔字符串 */
fun diagnosisToText(diagnosis: List<String>): String =
    if (diagnosis.isEmpty()) "无"
    else diagnosis.joinToString("、") { diagnosisLabelToText(it) }
