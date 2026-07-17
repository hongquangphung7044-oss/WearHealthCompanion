package com.wearhealth.companion.mobile.data

import com.wearhealth.companion.shared.EcgBinaryCodec
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.shared.MeasurementSerializer

/**
 * ECG 测量记录仓库
 *
 * 作为 UI 层与 Room 之间的中介：
 * - 写入时把 [EcgMeasurementTransfer] 转换为 [EcgMeasurementEntity]（波形二进制化）。
 * - 读取详情时把 [EcgMeasurementEntity] 反序列化为 [EcgMeasurementTransfer]（含完整波形）。
 */
class MeasurementRepository(private val dao: EcgMeasurementDao) {

    /** Insert a measurement idempotently. A retry replaces the same timestamp in place. */
    suspend fun upsertByTimestamp(transfer: EcgMeasurementTransfer): Long {
        val entity = EcgMeasurementEntity(
            id = dao.getIdByTimestamp(transfer.timestamp) ?: 0,
            timestamp = transfer.timestamp,
            diagnosis = transfer.diagnosis.joinToString(","),
            possibleDiagnoses = transfer.possibleDiagnoses.joinToString(","),
            isReverse = transfer.isReverse,
            avgHeartRate = transfer.avgHeartRate,
            minHeartRate = transfer.minHeartRate,
            maxHeartRate = transfer.maxHeartRate,
            signalQuality = transfer.signalQuality,
            isAbnormal = transfer.isAbnormal,
            avgQrs = transfer.avgQrs,
            avgP = transfer.avgP,
            prInterval = transfer.prInterval,
            avgQt = transfer.avgQt,
            avgQtc = transfer.avgQtc,
            pacCount = transfer.pacCount,
            pvcCount = transfer.pvcCount,
            sampleRate = transfer.sampleRate,
            rawDataJson = MeasurementSerializer.toJson(transfer),
            rawEcgBytes = EcgBinaryCodec.encode(transfer.rawEcgData),
            downsampledEcgBytes = EcgBinaryCodec.encode(transfer.downsampledEcg),
            analysisMethod = transfer.analysisMethod,
            aiReport = transfer.aiReport,
            tavilyStatus = transfer.tavilyStatus,
            ppgReferenceHr = transfer.ppgReferenceHr,
            sdnnMs = transfer.sdnnMs,
            rmssdMs = transfer.rmssdMs,
            pnn50Pct = transfer.pnn50Pct,
            processedByAlgorithm = transfer.processedByAlgorithm,
        )
        return dao.insert(entity)
    }

    /** Compatibility alias for the existing Data Layer receiver. */
    suspend fun insert(transfer: EcgMeasurementTransfer): Long = upsertByTimestamp(transfer)

    /** 获取全部测量记录（按时间倒序，响应式） */
    fun getAll(): kotlinx.coroutines.flow.Flow<List<EcgMeasurementEntity>> = dao.getAll()

    /**
     * 按主键查询单条记录并反序列化为 [EcgMeasurementTransfer]（含完整原始波形）
     * @return 不存在返回 null
     */
    suspend fun getById(id: Long): EcgMeasurementTransfer? {
        val entity = dao.getById(id) ?: return null
        // 从 JSON 恢复标量字段（rawEcgData 为空），再补充二进制波形
        val transfer = MeasurementSerializer.fromJson(entity.rawDataJson)
        return transfer.copy(
            rawEcgData = EcgBinaryCodec.decode(entity.rawEcgBytes),
            downsampledEcg = EcgBinaryCodec.decode(entity.downsampledEcgBytes),
        )
    }

    /** 按主键删除 */
    suspend fun delete(id: Long) = dao.delete(id)

    /** 删除全部 */
    suspend fun deleteAll() = dao.deleteAll()
}
