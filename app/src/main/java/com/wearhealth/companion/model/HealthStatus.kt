package com.wearhealth.companion.model

/**
 * 健康分析状态：由本地算法/模型输出
 */
sealed class HealthStatus {
    object Normal : HealthStatus()
    data class HighHeartRate(val bpm: Int) : HealthStatus()
    data class LowHeartRate(val bpm: Int) : HealthStatus()
    data class LowHRV(val hrvMs: Double) : HealthStatus()
    data class LowSpO2(val spo2: Int) : HealthStatus()
    data class IrregularRhythm(val confidence: Double) : HealthStatus()
    data class Unknown(val reason: String) : HealthStatus()
}

/**
 * 阈值配置（可后续做成用户可调）
 */
object HealthThresholds {
    const val HR_HIGH = 120          // bpm，静息状态下过高
    const val HR_LOW = 45            // bpm，静息状态下过低
    const val HRV_LOW = 20.0         // ms，RMSSD 低于此值提示压力/疲劳
    const val SPO2_LOW = 90          // %，血氧过低
    const val IRREGULAR_THRESHOLD = 0.7   // 心律不齐模型置信度阈值
    const val ACCEL_RESTING = 1.2    // m/s²，低于此值认为静息
}

/**
 * 将状态转成中文展示文本
 */
fun HealthStatus.toDisplayText(): String = when (this) {
    is HealthStatus.Normal -> "✓ 指标正常"
    is HealthStatus.HighHeartRate -> "⚠️ 心率过高 ($bpm bpm)"
    is HealthStatus.LowHeartRate -> "⚠️ 心率过低 ($bpm bpm)"
    is HealthStatus.LowHRV -> "⚠️ HRV 偏低 (${"%.1f".format(hrvMs)} ms)"
    is HealthStatus.LowSpO2 -> "⚠️ 血氧偏低 ($spo2%)"
    is HealthStatus.IrregularRhythm -> "⚠️ 疑似心律不齐 (${(confidence * 100).toInt()}%)"
    is HealthStatus.Unknown -> "○ 数据不足"
}
