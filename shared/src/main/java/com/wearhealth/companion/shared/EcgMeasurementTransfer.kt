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
 * @param analysisMethod 分析方式："heartvoice"（专业API）/ "ds_flash_fast" / "ds_flash_balanced" / "ds_pro_max"
 * @param aiReport DeepSeek 生成的 JSON 报告（仅 DS 分析方式有值；HeartVoice 为空字符串）
 */
data class EcgMeasurementTransfer(
    val timestamp: Long,
    val diagnosis: List<String>,
    val possibleDiagnoses: List<String> = emptyList(),
    val isReverse: Boolean = false,
    val avgHeartRate: Int,
    val minHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val signalQuality: Double,
    val isAbnormal: Boolean,
    val avgQrs: Int,
    val avgP: Int = 0,
    val prInterval: Int,
    val avgQt: Int,
    val avgQtc: Int,
    val pacCount: Int,
    val pvcCount: Int,
    val rawEcgData: List<Int>,
    val downsampledEcg: List<Int> = emptyList(),
    val sampleRate: Int = 500,
    val analysisMethod: String = "heartvoice",
    val aiReport: String = "",
    val tavilyStatus: String = "",   // Tavily 联网检索状态（仅 DS Max 档有值）
    val ppgReferenceHr: Int = 0,     // PPG 绿光参考心率（0=未采集/不可用）
    // HRV 时域指标（raw 模式由 DS 自算，算法模式由本地算法计算，0=未测）
    val sdnnMs: Double = 0.0,        // SDNN，自主神经总张力
    val rmssdMs: Double = 0.0,       // RMSSD，副交感张力
    val pnn50Pct: Double = 0.0,      // pNN50%，副交感张力
    // 是否经过本地算法处理（feature/raw-ecg-to-ds 分支新增）
    // true=经过 EcgFeatureExtractor 算法估测（间期/HRV/形态等本地算出）
    // false=原始波形直传 DS 分析（ds_raw 方式，未经本地算法）
    // 手机端据此区分数据来源，UI 展示时标注"算法分析"或"原始波形分析"
    val processedByAlgorithm: Boolean = true,
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
