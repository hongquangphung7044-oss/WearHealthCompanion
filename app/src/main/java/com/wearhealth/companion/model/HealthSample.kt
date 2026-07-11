package com.wearhealth.companion.model

import kotlin.math.sqrt

/**
 * 单次健康样本：包含心率、HRV、血氧、加速度幅值
 * @param timestampMs 采集时间戳（毫秒）
 * @param heartRate    心率 (bpm)，-1 表示无效
 * @param hrvMs        HRV (RMSSD, 毫秒)，-1 表示无效
 * @param spo2         血氧 (%)，-1 表示无效
 * @param accelMag     加速度幅值 (m/s²)，用于运动状态判断
 */
data class HealthSample(
    val timestampMs: Long,
    val heartRate: Int,
    val hrvMs: Double,
    val spo2: Int,
    val accelMag: Double,
) {
    companion object {
        val INVALID = HealthSample(
            timestampMs = 0L,
            heartRate = -1,
            hrvMs = -1.0,
            spo2 = -1,
            accelMag = 0.0,
        )
    }
}

/**
 * 从一段 RR 间期（毫秒）计算 RMSSD（常用的 HRV 指标）
 */
fun computeRmssd(rrIntervalsMs: List<Long>): Double {
    if (rrIntervalsMs.size < 2) return 0.0
    var sumSq = 0.0
    for (i in 1 until rrIntervalsMs.size) {
        val diff = rrIntervalsMs[i].toDouble() - rrIntervalsMs[i - 1].toDouble()
        sumSq += diff * diff
    }
    return sqrt(sumSq / (rrIntervalsMs.size - 1))
}

/**
 * 加速度幅值：sqrt(x² + y² + z²)
 */
fun computeAccelMag(x: Float, y: Float, z: Float): Double {
    return sqrt((x * x + y * y + z * z).toDouble())
}
