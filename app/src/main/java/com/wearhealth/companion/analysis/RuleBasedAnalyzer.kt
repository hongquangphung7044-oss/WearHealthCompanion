package com.wearhealth.companion.analysis

import com.wearhealth.companion.model.HealthSample
import com.wearhealth.companion.model.HealthStatus
import com.wearhealth.companion.model.HealthThresholds

/**
 * 本地规则引擎：基于阈值做异常检测
 * 优先级：HighHR > LowHR > LowSpO2 > LowHRV > Normal
 */
class RuleBasedAnalyzer {

    fun analyze(sample: HealthSample): HealthStatus {
        if (sample.heartRate <= 0) {
            return HealthStatus.Unknown("无心率数据")
        }

        // 静息状态下才做心率过高/过低判断（避免误报运动状态）
        val isResting = sample.accelMag < HealthThresholds.ACCEL_RESTING

        return when {
            isResting && sample.heartRate > HealthThresholds.HR_HIGH ->
                HealthStatus.HighHeartRate(sample.heartRate)
            isResting && sample.heartRate < HealthThresholds.HR_LOW ->
                HealthStatus.LowHeartRate(sample.heartRate)
            sample.spo2 > 0 && sample.spo2 < HealthThresholds.SPO2_LOW ->
                HealthStatus.LowSpO2(sample.spo2)
            sample.hrvMs > 0 && sample.hrvMs < HealthThresholds.HRV_LOW ->
                HealthStatus.LowHRV(sample.hrvMs)
            else -> HealthStatus.Normal
        }
    }
}
