package com.wearhealth.companion.shared

import com.google.android.gms.wearable.DataMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * ECG 测量数据序列化工具
 *
 * 提供两种序列化方式：
 * 1. DataMap（推荐）：用于 Wearable Data Layer 的 putDataItem，类型安全
 * 2. JSON：用于 Room 数据库存储和 PDF 导出
 */
object MeasurementSerializer {

    // ===== DataMap 序列化（用于 Wearable Data Layer 传输）=====

    /**
     * 将 ECG 测量数据写入 DataMap
     * @return 填充好的 DataMap，可直接用于 PutDataMapRequest
     */
    fun toDataMap(data: EcgMeasurementTransfer): DataMap = DataMap().apply {
        putLong(DataLayerPaths.KEY_TIMESTAMP, data.timestamp)
        putString(DataLayerPaths.KEY_DIAGNOSIS, data.diagnosis.joinToString(","))
        putString(DataLayerPaths.KEY_POSSIBLE_DIAGNOSES, data.possibleDiagnoses.joinToString(","))
        putBoolean(DataLayerPaths.KEY_IS_REVERSE, data.isReverse)
        putInt(DataLayerPaths.KEY_AVG_HR, data.avgHeartRate)
        putInt(DataLayerPaths.KEY_MIN_HR, data.minHeartRate)
        putInt(DataLayerPaths.KEY_MAX_HR, data.maxHeartRate)
        putDouble(DataLayerPaths.KEY_SIGNAL_QUALITY, data.signalQuality)
        putBoolean(DataLayerPaths.KEY_IS_ABNORMAL, data.isAbnormal)
        putInt(DataLayerPaths.KEY_AVG_QRS, data.avgQrs)
        putInt(DataLayerPaths.KEY_AVG_P, data.avgP)
        putInt(DataLayerPaths.KEY_PR_INTERVAL, data.prInterval)
        putInt(DataLayerPaths.KEY_AVG_QT, data.avgQt)
        putInt(DataLayerPaths.KEY_AVG_QTC, data.avgQtc)
        putInt(DataLayerPaths.KEY_PAC_COUNT, data.pacCount)
        putInt(DataLayerPaths.KEY_PVC_COUNT, data.pvcCount)
        putInt(DataLayerPaths.KEY_SAMPLE_RATE, data.sampleRate)
        putString(DataLayerPaths.KEY_ANALYSIS_METHOD, data.analysisMethod)
        if (data.aiReport.isNotEmpty()) {
            putString(DataLayerPaths.KEY_AI_REPORT, data.aiReport)
        }
        if (data.tavilyStatus.isNotEmpty()) {
            putString(DataLayerPaths.KEY_TAVILY_STATUS, data.tavilyStatus)
        }
        if (data.ppgReferenceHr > 0) {
            putInt(DataLayerPaths.KEY_PPG_HR, data.ppgReferenceHr)
        }
        // A retry with the same timestamp and ECG bytes must still generate TYPE_CHANGED so the
        // phone can persist idempotently and resend an ACK that may have been lost previously.
        putString(DataLayerPaths.KEY_TRANSFER_NONCE, java.util.UUID.randomUUID().toString())
        // 原始波形用二进制编码（60KB），比 JSON 文本小 40%
        if (data.rawEcgData.isNotEmpty()) {
            putByteArray(DataLayerPaths.KEY_RAW_ECG, EcgBinaryCodec.encode(data.rawEcgData))
        }
        // 降采样波形也用二进制
        if (data.downsampledEcg.isNotEmpty()) {
            putByteArray(DataLayerPaths.KEY_DOWNSAMPLED_ECG, EcgBinaryCodec.encode(data.downsampledEcg))
        }
    }

    /**
     * 从 DataMap 读取 ECG 测量数据
     */
    fun fromDataMap(dataMap: DataMap): EcgMeasurementTransfer {
        val diagnosisStr = dataMap.getString(DataLayerPaths.KEY_DIAGNOSIS, "")
        val diagnosis = if (diagnosisStr.isBlank()) emptyList() else diagnosisStr.split(",")
        val possibleStr = dataMap.getString(DataLayerPaths.KEY_POSSIBLE_DIAGNOSES, "")
        val possibleDiagnoses = if (possibleStr.isBlank()) emptyList() else possibleStr.split(",")

        val rawEcgBytes = dataMap.getByteArray(DataLayerPaths.KEY_RAW_ECG)
        val rawEcg = if (rawEcgBytes != null) EcgBinaryCodec.decode(rawEcgBytes) else emptyList()

        val downsampledBytes = dataMap.getByteArray(DataLayerPaths.KEY_DOWNSAMPLED_ECG)
        val downsampled = if (downsampledBytes != null) EcgBinaryCodec.decode(downsampledBytes) else emptyList()

        return EcgMeasurementTransfer(
            timestamp = dataMap.getLong(DataLayerPaths.KEY_TIMESTAMP),
            diagnosis = diagnosis,
            possibleDiagnoses = possibleDiagnoses,
            isReverse = dataMap.getBoolean(DataLayerPaths.KEY_IS_REVERSE, false),
            avgHeartRate = dataMap.getInt(DataLayerPaths.KEY_AVG_HR),
            minHeartRate = dataMap.getInt(DataLayerPaths.KEY_MIN_HR),
            maxHeartRate = dataMap.getInt(DataLayerPaths.KEY_MAX_HR),
            signalQuality = dataMap.getDouble(DataLayerPaths.KEY_SIGNAL_QUALITY),
            isAbnormal = dataMap.getBoolean(DataLayerPaths.KEY_IS_ABNORMAL),
            avgQrs = dataMap.getInt(DataLayerPaths.KEY_AVG_QRS),
            avgP = dataMap.getInt(DataLayerPaths.KEY_AVG_P),
            prInterval = dataMap.getInt(DataLayerPaths.KEY_PR_INTERVAL),
            avgQt = dataMap.getInt(DataLayerPaths.KEY_AVG_QT),
            avgQtc = dataMap.getInt(DataLayerPaths.KEY_AVG_QTC),
            pacCount = dataMap.getInt(DataLayerPaths.KEY_PAC_COUNT),
            pvcCount = dataMap.getInt(DataLayerPaths.KEY_PVC_COUNT),
            rawEcgData = rawEcg,
            downsampledEcg = downsampled,
            sampleRate = dataMap.getInt(DataLayerPaths.KEY_SAMPLE_RATE, 500),
            analysisMethod = dataMap.getString(DataLayerPaths.KEY_ANALYSIS_METHOD, "heartvoice"),
            aiReport = dataMap.getString(DataLayerPaths.KEY_AI_REPORT, ""),
            tavilyStatus = dataMap.getString(DataLayerPaths.KEY_TAVILY_STATUS, ""),
            ppgReferenceHr = dataMap.getInt(DataLayerPaths.KEY_PPG_HR, 0),
        )
    }

    // ===== JSON 序列化（用于 Room 存储 / PDF 导出）=====

    /**
     * 序列化为 JSON 字符串（不含原始波形，用于数据库索引行）
     * 原始波形单独存储为 ByteArray 列
     */
    fun toJson(data: EcgMeasurementTransfer): String {
        val diagArray = JSONArray()
        data.diagnosis.forEach { diagArray.put(it) }
        val possibleArray = JSONArray()
        data.possibleDiagnoses.forEach { possibleArray.put(it) }
        return JSONObject().apply {
            put("timestamp", data.timestamp)
            put("diagnosis", diagArray)
            put("possibleDiags", possibleArray)
            put("isReverse", data.isReverse)
            put("avgHr", data.avgHeartRate)
            put("minHr", data.minHeartRate)
            put("maxHr", data.maxHeartRate)
            put("sq", data.signalQuality)
            put("ab", data.isAbnormal)
            put("qrs", data.avgQrs)
            put("avgP", data.avgP)
            put("pr", data.prInterval)
            put("qt", data.avgQt)
            put("qtc", data.avgQtc)
            put("pac", data.pacCount)
            put("pvc", data.pvcCount)
            put("sampleRate", data.sampleRate)
            put("analysisMethod", data.analysisMethod)
            if (data.aiReport.isNotEmpty()) {
                put("aiReport", data.aiReport)
            }
            if (data.tavilyStatus.isNotEmpty()) {
                put("tavilyStatus", data.tavilyStatus)
            }
            if (data.ppgReferenceHr > 0) {
                put("ppgHr", data.ppgReferenceHr)
            }
        }.toString()
    }

    /**
     * 从 JSON 字符串反序列化（不含原始波形）
     */
    fun fromJson(json: String): EcgMeasurementTransfer {
        val o = JSONObject(json)
        val diagArr = o.getJSONArray("diagnosis")
        val diagnosis = mutableListOf<String>()
        for (i in 0 until diagArr.length()) diagnosis.add(diagArr.getString(i))
        val possibleArr = o.optJSONArray("possibleDiags") ?: JSONArray()
        val possibleDiagnoses = mutableListOf<String>()
        for (i in 0 until possibleArr.length()) possibleDiagnoses.add(possibleArr.getString(i))

        return EcgMeasurementTransfer(
            timestamp = o.getLong("timestamp"),
            diagnosis = diagnosis,
            possibleDiagnoses = possibleDiagnoses,
            isReverse = o.optBoolean("isReverse", false),
            avgHeartRate = o.optInt("avgHr"),
            minHeartRate = o.optInt("minHr"),
            maxHeartRate = o.optInt("maxHr"),
            signalQuality = o.optDouble("sq", 0.0),
            isAbnormal = o.optBoolean("ab"),
            avgQrs = o.optInt("qrs"),
            avgP = o.optInt("avgP"),
            prInterval = o.optInt("pr"),
            avgQt = o.optInt("qt"),
            avgQtc = o.optInt("qtc"),
            pacCount = o.optInt("pac"),
            pvcCount = o.optInt("pvc"),
            rawEcgData = emptyList(),  // 原始波形从单独的列读取
            sampleRate = o.optInt("sampleRate", 500),
            analysisMethod = o.optString("analysisMethod", "heartvoice"),
            aiReport = o.optString("aiReport", ""),
            tavilyStatus = o.optString("tavilyStatus", ""),
            ppgReferenceHr = o.optInt("ppgHr", 0),
        )
    }
}
