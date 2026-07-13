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
 * 每条记录包含：时间戳、诊断、心率、关键参数、波形（降采样）、信号质量
 */
class EcgHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存一条测量记录 */
    fun save(result: EcgAnalysisResult) {
        val list = getAll().toMutableList()
        list.add(0, HistoryItem(
            timestamp = System.currentTimeMillis(),
            diagnosis = result.diagnosis,
            avgHeartRate = result.avgHeartRate,
            minHeartRate = result.minHeartRate,
            maxHeartRate = result.maxHeartRate,
            signalQuality = result.signalQuality,
            isAbnormal = result.isAbnormal,
            avgQrs = result.avgQrs,
            prInterval = result.prInterval,
            avgQt = result.avgQt,
            avgQtc = result.avgQtc,
            pacCount = result.pacCount,
            pvcCount = result.pvcCount,
            ecgSamples = result.ecgSamples,
        ))
        // 最多保留 50 条
        val trimmed = if (list.size > 50) list.take(50) else list
        prefs.edit().putString(KEY_HISTORY, toJson(trimmed)).apply()
    }

    fun getAll(): List<HistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try { fromJson(json) } catch (e: Exception) { emptyList() }
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
            val diag = JSONArray()
            item.diagnosis.forEach { diag.put(it) }
            arr.put(JSONObject().apply {
                put("ts", item.timestamp)
                put("diag", diag)
                put("hr", item.avgHeartRate)
                put("minhr", item.minHeartRate)
                put("maxhr", item.maxHeartRate)
                put("sq", item.signalQuality)
                put("ab", item.isAbnormal)
                put("qrs", item.avgQrs)
                put("pr", item.prInterval)
                put("qt", item.avgQt)
                put("qtc", item.avgQtc)
                put("pac", item.pacCount)
                put("pvc", item.pvcCount)
                put("samples", samples)
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
            val samplesArr = o.optJSONArray("samples") ?: JSONArray()
            val samples = mutableListOf<Int>()
            for (j in 0 until samplesArr.length()) samples.add(samplesArr.getInt(j))
            list.add(HistoryItem(
                timestamp = o.getLong("ts"),
                diagnosis = diag,
                avgHeartRate = o.optInt("hr"),
                minHeartRate = o.optInt("minhr"),
                maxHeartRate = o.optInt("maxhr"),
                signalQuality = o.optDouble("sq", 0.0),
                isAbnormal = o.optBoolean("ab"),
                avgQrs = o.optInt("qrs"),
                prInterval = o.optInt("pr"),
                avgQt = o.optInt("qt"),
                avgQtc = o.optInt("qtc"),
                pacCount = o.optInt("pac"),
                pvcCount = o.optInt("pvc"),
                ecgSamples = samples,
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
    val ecgSamples: List<Int>,
)
