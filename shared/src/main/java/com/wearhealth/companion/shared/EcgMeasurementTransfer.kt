package com.wearhealth.companion.shared

import java.nio.ByteBuffer
import java.nio.IntBuffer

/**
 * ECG 测量数据传输模型
 *
 * 手表采集 + 分析完成后，将此对象通过 Wearable Data Layer 发送到手机。
 * 包含完整的原始波形数据（15000 点，约 60KB 二进制）。
 *
 * @param timestamp 测量时间戳（毫秒）
 * @param diagnosis 诊断标签列表，如 ["SN"]=窦性心律, ["AF"]=房颤
 * @param avgHeartRate 平均心率
 * @param minHeartRate 最低心率（本地 R-R 间期计算）
 * @param maxHeartRate 最高心率（本地 R-R 间期计算）
 * @param signalQuality 信号质量评分（0~1）
 * @param isAbnormal 是否异常
 * @param avgQrs QRS 宽度 ms
 * @param prInterval PR 间期 ms
 * @param avgQt QT 间期 ms
 * @param avgQtc 校正 QT 间期 ms
 * @param pacCount 房性早搏次数
 * @param pvcCount 室性早搏次数
 * @param rawEcgData 完整原始 ECG 波形（mV×1000 整数，500Hz × 30s ≈ 15000 点）
 * @param downsampledEcg 降采样波形（约 400 点，用于列表缩略图）
 * @param sampleRate 采样率 Hz
 */
data class EcgMeasurementTransfer(
    val timestamp: Long,
    val diagnosis: List<String>,
    val avgHeartRate: Int,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val signalQuality: Double,
    val isAbnormal: Boolean,
    val avgQrs: Int,
    val prInterval: Int,
    val avgQt: Int,
    val avgQtc: Int,
    val pacCount: Int,
    val pvcCount: Int,
    val rawEcgData: List<Int>,
    val downsampledEcg: List<Int> = emptyList(),
    val sampleRate: Int = 500,
)

/**
 * Int 列表 ↔ ByteArray 转换工具
 *
 * 用于 DataMap.putByteArray() 传输原始 ECG 波形。
 * 二进制格式比 JSON 文本小约 40%（15000 个 int: 60KB vs ~83KB）。
 */
object EcgBinaryCodec {

    /** List<Int> → ByteArray（每个 int 4 字节，大端序） */
    fun encode(list: List<Int>): ByteArray {
        val buffer = ByteBuffer.allocate(list.size * 4)
        val intBuffer: IntBuffer = buffer.asIntBuffer()
        intBuffer.put(list.toIntArray())
        return buffer.array()
    }

    /** ByteArray → List<Int> */
    fun decode(bytes: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(bytes)
        val intBuffer = buffer.asIntBuffer()
        val array = IntArray(intBuffer.remaining())
        intBuffer.get(array)
        return array.toList()
    }
}
