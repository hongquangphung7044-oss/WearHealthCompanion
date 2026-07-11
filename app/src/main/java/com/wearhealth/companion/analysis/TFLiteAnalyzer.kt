package com.wearhealth.companion.analysis

import android.content.Context
import com.wearhealth.companion.model.HealthSample
import com.wearhealth.companion.model.HealthStatus
import com.wearhealth.companion.model.HealthThresholds
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite 模型推理框架（预留接口）
 *
 * 当前行为：
 * - 尝试从 assets 加载 `health_model.tflite`
 * - 若模型存在：把最近 N 个样本特征化后送入模型推理
 * - 若模型不存在：回退到 RuleBasedAnalyzer
 *
 * 模型输入（示例，后续按实际训练的模型调整）：
 *   shape = [1, 5]  → [heartRate, hrv, spo2, accelMag, isResting]
 * 模型输出：
 *   shape = [1, 3]  → [normal, irregular, stress] 三个类别概率
 */
class TFLiteAnalyzer(context: Context) {

    private val ruleAnalyzer = RuleBasedAnalyzer()
    private var interpreter: Interpreter? = null
    private val inputFeatureCount = 5
    private val outputClassCount = 3

    init {
        try {
            val asset = context.assets.openFd(MODEL_FILENAME)
            val buffer: ByteBuffer = FileInputStream(asset.fileDescriptor.file).channel
                .map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(buffer, options)
            android.util.Log.i(TAG, "TFLite 模型加载成功: $MODEL_FILENAME")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "未找到 TFLite 模型 ($MODEL_FILENAME)，回退到规则引擎")
        }
    }

    fun analyze(sample: HealthSample): HealthStatus {
        val model = interpreter ?: return ruleAnalyzer.analyze(sample)

        // 构造输入
        val input = ByteBuffer.allocateDirect(4 * inputFeatureCount)
            .order(ByteOrder.nativeOrder())
        val isResting = if (sample.accelMag < HealthThresholds.ACCEL_RESTING) 1f else 0f
        input.putFloat(normalizeHr(sample.heartRate))
        input.putFloat(normalizeHrv(sample.hrvMs))
        input.putFloat(normalizeSpo2(sample.spo2))
        input.putFloat(normalizeAccel(sample.accelMag))
        input.putFloat(isResting)
        input.rewind()

        // 输出
        val output = ByteBuffer.allocateDirect(4 * outputClassCount)
            .order(ByteOrder.nativeOrder())

        try {
            model.run(input, output)
            output.rewind()
            val normal = output.float
            val irregular = output.float
            val stress = output.float
            android.util.Log.d(TAG, "推理结果: normal=$normal, irregular=$irregular, stress=$stress")

            return when {
                irregular > HealthThresholds.IRREGULAR_THRESHOLD ->
                    HealthStatus.IrregularRhythm(irregular.toDouble())
                stress > HealthThresholds.IRREGULAR_THRESHOLD ->
                    HealthStatus.LowHRV(sample.hrvMs)  // 压力大 → 用 HRV 偏低表达
                else -> HealthStatus.Normal
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "推理失败，回退规则引擎", e)
            return ruleAnalyzer.analyze(sample)
        }
    }

    fun close() {
        interpreter?.close()
    }

    // 简单归一化（后续按训练时的统计量调整）
    private fun normalizeHr(hr: Int) = (hr.coerceIn(30, 200) - 30) / 170f
    private fun normalizeHrv(hrv: Double) = hrv.coerceIn(0.0, 200.0).toFloat() / 200f
    private fun normalizeSpo2(spo2: Int) = spo2.coerceIn(70, 100) / 100f
    private fun normalizeAccel(a: Double) = a.coerceIn(0.0, 30.0).toFloat() / 30f

    companion object {
        private const val TAG = "TFLiteAnalyzer"
        private const val MODEL_FILENAME = "health_model.tflite"
    }
}
