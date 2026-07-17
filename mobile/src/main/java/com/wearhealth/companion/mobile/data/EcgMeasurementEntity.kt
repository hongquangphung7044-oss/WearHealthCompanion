package com.wearhealth.companion.mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ECG 测量记录的 Room 实体
 *
 * 手表通过 Wearable Data Layer 发送 [com.wearhealth.companion.shared.EcgMeasurementTransfer]，
 * 手机端收到后转换为该实体存入 Room 数据库。
 *
 * - 标量字段（心率、诊断、间期等）通过 [com.wearhealth.companion.shared.MeasurementSerializer.toJson]
 *   序列化为 [rawDataJson]，避免重复维护字段映射。
 * - 原始波形（约 15000 点）用二进制存储为 [rawEcgBytes]，比 JSON 文本小约 40%。
 * - 降采样波形（列表缩略图）存为 [downsampledEcgBytes]。
 */
@Entity(
    tableName = "ecg_measurements",
    indices = [Index(value = ["timestamp"], unique = true)],
)
data class EcgMeasurementEntity(
    /** 主键，自增 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 测量时间戳（毫秒） */
    val timestamp: Long,
    /** 诊断标签（逗号分隔），如 "SN,VPB" */
    val diagnosis: String,
    /** API 返回的可能诊断标签（逗号分隔） */
    @ColumnInfo(defaultValue = "''")
    val possibleDiagnoses: String,
    /** API 检测到导联可能拿反 */
    @ColumnInfo(defaultValue = "0")
    val isReverse: Boolean,
    /** 平均心率 bpm */
    val avgHeartRate: Int,
    /** 最低心率 bpm */
    val minHeartRate: Int,
    /** 最高心率 bpm */
    val maxHeartRate: Int,
    /** 信号质量评分 0~1 */
    val signalQuality: Double,
    /** 是否异常 */
    val isAbnormal: Boolean,
    /** QRS 宽度 ms */
    val avgQrs: Int,
    /** 平均 P 波宽度 ms */
    @ColumnInfo(defaultValue = "0")
    val avgP: Int,
    /** PR 间期 ms */
    val prInterval: Int,
    /** QT 间期 ms */
    val avgQt: Int,
    /** 校正 QT 间期 ms */
    val avgQtc: Int,
    /** 房性早搏次数 */
    val pacCount: Int,
    /** 室性早搏次数 */
    val pvcCount: Int,
    /** 采样率 Hz */
    val sampleRate: Int,
    /** MeasurementSerializer.toJson() 的结果，含全部标量字段（用于完整反序列化） */
    val rawDataJson: String,
    /** 原始波形二进制（EcgBinaryCodec.encode() 的结果） */
    val rawEcgBytes: ByteArray,
    /** 降采样波形二进制（用于列表缩略图） */
    val downsampledEcgBytes: ByteArray,
    /** 分析方式：heartvoice / ds_flash_fast / ds_flash_balanced / ds_pro_max */
    @ColumnInfo(defaultValue = "'heartvoice'")
    val analysisMethod: String = "heartvoice",
    /** DeepSeek 生成的 JSON 报告（仅 DS 分析方式有值；HeartVoice 为空字符串） */
    @ColumnInfo(defaultValue = "''")
    val aiReport: String = "",
    /** Tavily 联网检索状态（DS 均衡/Max 档有值） */
    @ColumnInfo(defaultValue = "''")
    val tavilyStatus: String = "",
    /** PPG 绿光参考心率（0=未采集/不可用） */
    @ColumnInfo(defaultValue = "0")
    val ppgReferenceHr: Int = 0,
    /** 是否经过本地算法处理（ds_raw 模式为 false，其余为 true） */
    @ColumnInfo(defaultValue = "1")
    val processedByAlgorithm: Boolean = true,
) {
    // ByteArray 的 equals/hashCode 需要重写，避免 Room/集合判等异常
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgMeasurementEntity) return false
        return id == other.id &&
                timestamp == other.timestamp &&
                diagnosis == other.diagnosis &&
                possibleDiagnoses == other.possibleDiagnoses &&
                isReverse == other.isReverse &&
                avgHeartRate == other.avgHeartRate &&
                minHeartRate == other.minHeartRate &&
                maxHeartRate == other.maxHeartRate &&
                signalQuality == other.signalQuality &&
                isAbnormal == other.isAbnormal &&
                avgQrs == other.avgQrs &&
                avgP == other.avgP &&
                prInterval == other.prInterval &&
                avgQt == other.avgQt &&
                avgQtc == other.avgQtc &&
                pacCount == other.pacCount &&
                pvcCount == other.pvcCount &&
                sampleRate == other.sampleRate &&
                rawDataJson == other.rawDataJson &&
                rawEcgBytes.contentEquals(other.rawEcgBytes) &&
                downsampledEcgBytes.contentEquals(other.downsampledEcgBytes) &&
                analysisMethod == other.analysisMethod &&
                aiReport == other.aiReport &&
                tavilyStatus == other.tavilyStatus &&
                ppgReferenceHr == other.ppgReferenceHr &&
                processedByAlgorithm == other.processedByAlgorithm
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + diagnosis.hashCode()
        result = 31 * result + possibleDiagnoses.hashCode()
        result = 31 * result + isReverse.hashCode()
        result = 31 * result + avgHeartRate
        result = 31 * result + minHeartRate
        result = 31 * result + maxHeartRate
        result = 31 * result + signalQuality.hashCode()
        result = 31 * result + isAbnormal.hashCode()
        result = 31 * result + avgQrs
        result = 31 * result + avgP
        result = 31 * result + prInterval
        result = 31 * result + avgQt
        result = 31 * result + avgQtc
        result = 31 * result + pacCount
        result = 31 * result + pvcCount
        result = 31 * result + sampleRate
        result = 31 * result + rawDataJson.hashCode()
        result = 31 * result + rawEcgBytes.contentHashCode()
        result = 31 * result + downsampledEcgBytes.contentHashCode()
        result = 31 * result + analysisMethod.hashCode()
        result = 31 * result + aiReport.hashCode()
        result = 31 * result + tavilyStatus.hashCode()
        result = 31 * result + ppgReferenceHr
        result = 31 * result + processedByAlgorithm.hashCode()
        return result
    }
}
