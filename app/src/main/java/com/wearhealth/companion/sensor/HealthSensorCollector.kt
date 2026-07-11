package com.wearhealth.companion.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearhealth.companion.model.HealthSample
import com.wearhealth.companion.model.computeAccelMag
import com.wearhealth.companion.model.computeRmssd
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Wear OS 传感器采集器
 *
 * 通过 Android SensorManager 采集：
 * - 心率 (TYPE_HEART_RATE)
 * - 加速度 (TYPE_ACCELEROMETER) → 用于运动状态判断
 * - 血氧 (TYPE_OXYGEN_SATURATION, API 21+ 但部分设备才支持)
 *
 * 注意：
 * - HRV 需要 RR 间期；标准 Android 仅提供心率数值。这里用「相邻心率样本推算 RR」
 *   作为粗略估算，正式版应改用 Health Services API 的 HrPing 数据。
 * - ECG 原始波形：Wear OS 公开 API 不提供，需 Samsung 私有 SDK。本类暂不采集。
 */
class HealthSensorCollector(private val context: Context) {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 最近一批 RR 间期（ms），用于估算 HRV */
    private val recentRrMs = ArrayDeque<Long>(MAX_RR_SAMPLES)
    private var lastHeartBeatTimeMs = 0L
    private var latestHeartRate = -1
    private var latestSpo2 = -1

    /**
     * 启动采集，返回 HealthSample 的 Flow
     */
    fun collect(): Flow<HealthSample> = callbackFlow {
        // 权限检查
        if (!hasBodySensorsPermission()) {
            Log.w(TAG, "缺少 BODY_SENSORS 权限，无法采集")
            trySend(HealthSample.INVALID.copy(timestampMs = System.currentTimeMillis()))
            awaitClose { }
            return@callbackFlow
        }

        val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // TYPE_OXYGEN_SATURATION 常量值 = 26，部分旧 SDK 不暴露该常量，用反射安全获取
        val spo2Sensor = sensorManager.getDefaultSensor(SPO2_SENSOR_TYPE)

        if (heartRateSensor == null) {
            Log.e(TAG, "设备无心率传感器")
        }

        var currentAccelMag = 0.0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_HEART_RATE -> {
                        val bpm = event.values[0].toInt()
                        if (bpm > 0) {
                            latestHeartRate = bpm
                            // 用「相邻心率间隔」近似 RR，仅作 HRV 粗略估算
                            val now = System.currentTimeMillis()
                            if (lastHeartBeatTimeMs > 0) {
                                val interval = now - lastHeartBeatTimeMs
                                if (interval in 300..2000) { // 30~200 bpm 合理范围
                                    recentRrMs.addLast(interval)
                                    if (recentRrMs.size > MAX_RR_SAMPLES) {
                                        recentRrMs.removeFirst()
                                    }
                                }
                            }
                            lastHeartBeatTimeMs = now
                            pushSample(currentAccelMag)
                        }
                    }
                    SPO2_SENSOR_TYPE -> {
                        val spo2 = event.values[0].toInt()
                        if (spo2 in 70..100) latestSpo2 = spo2
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        currentAccelMag = computeAccelMag(x, y, z)
                    }
                }
            }

            private fun pushSample(accelMag: Double) {
                val hrv = computeRmssd(recentRrMs.toList())
                val sample = HealthSample(
                    timestampMs = System.currentTimeMillis(),
                    heartRate = latestHeartRate,
                    hrvMs = hrv,
                    spo2 = latestSpo2,
                    accelMag = accelMag,
                )
                trySend(sample)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        heartRateSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "注册心率传感器")
        }
        spo2Sensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "注册血氧传感器")
        }
        accelSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "注册加速度传感器")
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            Log.i(TAG, "停止传感器采集")
        }
    }.conflate()

    private fun hasBodySensorsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "HealthSensorCollector"
        private const val MAX_RR_SAMPLES = 30
        // Sensor.TYPE_OXYGEN_SATURATION 的常量值（API 21+，但常量在 API 31 才公开）
        private const val SPO2_SENSOR_TYPE = 26
    }
}
