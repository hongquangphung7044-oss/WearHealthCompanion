package com.wearhealth.companion.data

import android.content.Context
import com.wearhealth.companion.model.EcgAnalysisResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ECG 测量历史记录存储
 *
 * 使用 SharedPreferences 以 JSON 数组形式存储，简单可靠。
 * 每条记录包含：时间戳、诊断、心率、关键参数、波形（降采样，用于手表显示）、
 * 完整原始波形（用于传送到手机）、信号质量、是否已同步到手机。
 */
class EcgHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 保存一条测量记录
     *
     * @param result ECG 分析结果
     * @param rawEcgData 完整原始波形（不降采样，约 15000 点，用于传送到手机）
     */
    fun save(result: EcgAnalysisResult, rawEcgData: List<Int> = emptyList()): HistoryItem {
        val list = getAll().toMutableList()
        val saved = HistoryItem(
            timestamp = System.currentTimeMillis(),
            diagnosis = result.diagnosis,
            possibleDiagnoses = result.possibleDiagnoses,
            isReverse = result.isReverse,
            avgHeartRate = result.avgHeartRate,
            minHeartRate = result.minHeartRate,
            maxHeartRate = result.maxHeartRate,
            signalQuality = result.signalQuality,
            isAbnormal = result.isAbnormal,
            avgQrs = result.avgQrs,
            avgP = result.avgP,
            prInterval = result.prInterval,
            avgQt = result.avgQt,
            avgQtc = result.avgQtc,
            pacCount = result.pacCount,
            pvcCount = result.pvcCount,
            ecgSamples = result.ecgSamples,
            rawEcgData = rawEcgData,
            syncedToPhone = false,
            analysisMethod = result.analysisMethod,
            aiReport = result.aiReport,
            tavilyStatus = result.tavilyStatus,
            ppgReferenceHr = result.ppgReferenceHr,
        )
        list.add(0, saved)
        // 最多保留 50 条
        val trimmed = if (list.size > 50) list.take(50) else list
        prefs.edit().putString(KEY_HISTORY, toJson(trimmed)).apply()
        return saved
    }

    fun getAll(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try { fromJson(json) } catch (e: Exception) { emptyList() }
    }

    /** 获取未同步到手机的记录 */
    fun getUnsynced(): List<HistoryItem> = getAll().filterNot { it.syncedToPhone }

    /** 标记指定时间戳的记录为已同步到手机 */
    fun markSynced(timestamp: Long): Boolean {
        val current = getAll()
        if (current.none { it.timestamp == timestamp }) return false
        val list = current.map { item ->
            if (item.timestamp == timestamp) item.copy(syncedToPhone = true) else item
        }
        prefs.edit().putString(KEY_HISTORY, toJson(list)).apply()
        return true
    }

    fun delete(timestamp: Long) {
        val list = getAll().filterNot { it.timestamp == timestamp }
        prefs.edit().putString(KEY_HISTORY, toJson(list)).apply()
    }

    fun deleteAll() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun toJson(list: List<HistoryItem>): String {
        val arr = JSONArray()
        list.forEach { item ->
            val samples = JSONArray()
            item.ecgSamples.forEach { samples.put(it) }
            val raw = JSONArray()
            item.rawEcgData.forEach { raw.put(it) }
            val diag = JSONArray()
            item.diagnosis.forEach { diag.put(it) }
            arr.put(JSONObject().apply {
                put("ts", item.timestamp)
                put("diag", diag)
                put("possible", JSONArray().apply { item.possibleDiagnoses.forEach { put(it) } })
                put("reverse", item.isReverse)
                put("hr", item.avgHeartRate)
                put("minhr", item.minHeartRate)
                put("maxhr", item.maxHeartRate)
                put("sq", item.signalQuality)
                put("ab", item.isAbnormal)
                put("qrs", item.avgQrs)
                put("avgP", item.avgP)
                put("pr", item.prInterval)
                put("qt", item.avgQt)
                put("qtc", item.avgQtc)
                put("pac", item.pacCount)
                put("pvc", item.pvcCount)
                put("samples", samples)
                put("sync", item.syncedToPhone)
                put("raw", raw)
                put("analysisMethod", item.analysisMethod)
                if (item.aiReport.isNotEmpty()) {
                    put("aiReport", item.aiReport)
                }
                if (item.tavilyStatus.isNotEmpty()) {
                    put("tavilyStatus", item.tavilyStatus)
                }
                if (item.ppgReferenceHr > 0) {
                    put("ppgHr", item.ppgReferenceHr)
                }
            })
        }
        return arr.toString()
    }

    private fun fromJson(json: String): List<HistoryItem> {
        val arr = JSONArray(json)
        val list = mutableListOf<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val diagArr = o.getJSONArray("diag")
            val diag = mutableListOf<String>()
            for (j in 0 until diagArr.length()) diag.add(diagArr.getString(j))
            val possibleArr = o.optJSONArray("possible") ?: JSONArray()
            val possible = mutableListOf<String>()
            for (j in 0 until possibleArr.length()) possible.add(possibleArr.getString(j))
            val samplesArr = o.optJSONArray("samples") ?: JSONArray()
            val samples = mutableListOf<Int>()
            for (j in 0 until samplesArr.length()) samples.add(samplesArr.getInt(j))
            // 完整原始波形（用于传送到手机，可能较大）
            val rawArr = o.optJSONArray("raw") ?: JSONArray()
            val raw = mutableListOf<Int>()
            for (j in 0 until rawArr.length()) raw.add(rawArr.getInt(j))
            list.add(HistoryItem(
                timestamp = o.getLong("ts"),
                diagnosis = diag,
                possibleDiagnoses = possible,
                isReverse = o.optBoolean("reverse", false),
                avgHeartRate = o.optInt("hr"),
                minHeartRate = o.optInt("minhr"),
                maxHeartRate = o.optInt("maxhr"),
                signalQuality = o.optDouble("sq", 0.0),
                isAbnormal = o.optBoolean("ab"),
                avgQrs = o.optInt("qrs"),
                avgP = o.optInt("avgP"),
                prInterval = o.optInt("pr"),
                avgQt = o.optInt("qt"),
                avgQtc = o.optInt("qtc"),
                pacCount = o.optInt("pac"),
                pvcCount = o.optInt("pvc"),
                ecgSamples = samples,
                rawEcgData = raw,
                syncedToPhone = o.optBoolean("sync", false),
                analysisMethod = o.optString("analysisMethod", "heartvoice"),
                aiReport = o.optString("aiReport", ""),
                tavilyStatus = o.optString("tavilyStatus", ""),
                ppgReferenceHr = o.optInt("ppgHr", 0),
            ))
        }
        return list
    }

    companion object {
        private const val PREFS_NAME = "ecg_history"
        private const val KEY_HISTORY = "records"

        fun formatTime(ts: Long): String {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(ts))
        }
    }
}

data class HistoryItem(
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
    val ecgSamples: List<Int>,                 // 降采样波形（用于手表显示）
    val rawEcgData: List<Int> = emptyList(),   // 完整原始波形（用于传送到手机，不降采样）
    val syncedToPhone: Boolean = false,        // 是否已同步到手机
    val analysisMethod: String = "heartvoice", // 分析方式：heartvoice / ds_*
    val aiReport: String = "",                 // DeepSeek JSON 报告（仅 DS 方式有值）
    val tavilyStatus: String = "",             // Tavily 联网检索状态（仅 DS Max 档有值）
    val ppgReferenceHr: Int = 0,               // PPG 绿光参考心率（0=未采集/不可用）
)
