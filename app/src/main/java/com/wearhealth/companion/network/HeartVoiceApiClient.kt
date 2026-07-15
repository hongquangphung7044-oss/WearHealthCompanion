package com.wearhealth.companion.network

import android.util.Log
import com.wearhealth.companion.model.EcgAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HeartVoice ECG 分析 API 客户端
 *
 * 接口文档: https://www.heartvoice.com.cn/aiCloud/docs/api/ecg-basic/
 * 单导联分析: POST /api/v1/basic/ecg/1-lead/analyze
 *
 * API Key 通过构造函数传入（由 ApiKeyManager 提供，支持手机端远程下发）。
 */
class HeartVoiceApiClient(
    private val apiKey: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isApiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 分析单导联 ECG 信号
     * @param ecgData ECG 信号数组（ADC 值）
     * @param sampleRate 采样率 Hz（Samsung SDK ECG = 500Hz）
     */
    suspend fun analyzeEcg(ecgData: List<Int>, sampleRate: Int = 500): Result<EcgAnalysisResult> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("未配置 HeartVoice API Key"))
            }

            try {
                val jsonArray = JSONArray()
                ecgData.forEach { jsonArray.put(it) }

                val jsonBody = JSONObject().apply {
                    put("ecgData", jsonArray)
                    put("ecgSampleRate", sampleRate)
                    // EcgCollector 将 mV 值放大 1000 倍存为整数，因此 adcGain=1000.0
                    // API 公式: voltage_mV = (sampleValue - adcZero) / adcGain
                    put("adcGain", 1000.0)
                    put("adcZero", 0.0)
                }

                val request = Request.Builder()
                    .url("$BASE_URL/ecg/1-lead/analyze")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                Log.i(TAG, "发送 ECG 分析请求: ${ecgData.size} 个采样点, ${sampleRate}Hz")

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "API 请求失败: HTTP ${response.code}")
                    return@withContext Result.failure(
                        RuntimeException("HeartVoice 请求失败（HTTP ${response.code}）")
                    )
                }

                val json = JSONObject(responseBody)
                val errorCode = json.optString("errorCode")
                if (errorCode != "0") {
                    val msg = json.optString("msg", "未知错误")
                    Log.e(TAG, "API 返回错误: $errorCode - $msg")
                    return@withContext Result.failure(RuntimeException("$msg (code=$errorCode)"))
                }

                val data = json.optJSONObject("data")
                    ?: return@withContext Result.failure(RuntimeException("响应缺少 data 字段"))

                val diagnosisList = mutableListOf<String>()
                val diagArray = data.optJSONArray("diagnosis")
                if (diagArray != null) {
                    for (i in 0 until diagArray.length()) {
                        diagnosisList.add(diagArray.getString(i))
                    }
                }

                val possibleDiagnoses = mutableListOf<String>()
                data.optJSONArray("possibleDiags")?.let { possible ->
                    for (i in 0 until possible.length()) possibleDiagnoses.add(possible.getString(i))
                }

                val result = EcgAnalysisResult(
                    isAbnormal = data.optBoolean("isAbnormal", false),
                    signalQuality = data.optString("sqGrade", "0").toDoubleOrNull() ?: 0.0,
                    diagnosis = diagnosisList,
                    possibleDiagnoses = possibleDiagnoses,
                    isReverse = data.optBoolean("isReverse", false),
                    avgHeartRate = data.optInt("avgHr", 0),
                    avgQrs = data.optInt("avgQrs", 0),
                    avgP = data.optInt("avgP", 0),
                    prInterval = data.optInt("prInterval", 0),
                    avgQt = data.optInt("avgQt", 0),
                    avgQtc = data.optInt("avgQtc", 0),
                    pacCount = data.optInt("pacCount", 0),
                    pvcCount = data.optInt("pvcCount", 0),
                    rawData = responseBody,
                )

                Log.i(TAG, "ECG 分析完成: ${result.diagnosis}, HR=${result.avgHeartRate}, " +
                        "质量=${result.signalQuality}, 异常=${result.isAbnormal}")

                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "ECG 分析异常", e)
                Result.failure(e)
            }
        }

    companion object {
        private const val TAG = "HeartVoiceApi"
        private const val BASE_URL = "https://api.heartvoice.com.cn/api/v1/basic"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
