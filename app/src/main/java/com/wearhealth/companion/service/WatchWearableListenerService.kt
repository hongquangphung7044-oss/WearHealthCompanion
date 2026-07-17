package com.wearhealth.companion.service

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearhealth.companion.data.ApiKeyManager
import com.wearhealth.companion.data.DeepSeekSettings
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.TavilySettings
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.DataLayerPaths
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.shared.MeasurementSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * 手表端 Wearable Data Layer 监听服务
 *
 * 职责：
 * 1. 接收手机端下发的 API Key（路径 /api_key）并保存到 ApiKeyManager
 * 2. 接收手机端的同步请求（路径 /sync_request），触发未传送数据的同步
 *
 * 通过 [ApiKeyManager.refreshTrigger] 通知 UI 刷新 API Key 状态。
 * 通过 [syncEvents] 通知 UI 显示真实同步提交结果或手机 ACK。
 */
class WatchWearableListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiKeyManager by lazy { ApiKeyManager(this) }
    private val historyRepo by lazy { EcgHistoryRepository(this) }
    private val dsSettings by lazy { DeepSeekSettings(this) }
    private val tavilySettings by lazy { TavilySettings(this) }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.i(TAG, "onDataChanged: ${dataEvents.count} 个事件")
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            val path = uri.path ?: continue
            Log.i(TAG, "Data 事件 path: $path, type: ${event.type}")

            // 只处理新增/变更的数据
            if (event.type != DataEvent.TYPE_CHANGED) continue

            // 检查是否为 API Key 下发（路径以 /api_key 开头）
            if (path.startsWith(DataLayerPaths.PATH_API_KEY)) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val rawKey = dataMap.getString(DataLayerPaths.KEY_API_KEY, "")
                    // Data Layer 下发同样归一化校验；失败不保存，不覆盖旧有效 Key。
                    val result = ApiKeyValidator.normalizeApiKey(rawKey)
                    val normalized = result.getOrNull()
                    if (normalized != null) {
                        Log.i(TAG, "收到手机下发的 API Key，保存中...")
                        apiKeyManager.saveApiKey(normalized)
                        // ApiKeyManager.saveApiKey 会触发 refreshTrigger，通知 UI 刷新
                    } else {
                        Log.w(TAG, "收到的 API Key 非法，未保存：${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 API Key 数据失败", e)
                }
            }

            // DeepSeek 设置下发（路径 /deepseek_settings）
            if (path.startsWith(DataLayerPaths.PATH_DEEPSEEK_SETTINGS)) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    // DataMap.getString 的默认值参数为非空类型，用 "" 兜底再转 null（表示"未下发，保留原值"）
                    val dsKey = dataMap.getString(DataLayerPaths.KEY_DS_API_KEY, "")
                        .takeIf { it.isNotBlank() }
                    val defaultModel = dataMap.getString(DataLayerPaths.KEY_DS_DEFAULT_MODEL, "")
                        .takeIf { it.isNotBlank() }
                    val defaultThinking = dataMap.getString(DataLayerPaths.KEY_DS_DEFAULT_THINKING, "")
                        .takeIf { it.isNotBlank() }
                    val userAge = dataMap.getInt(DataLayerPaths.KEY_DS_USER_AGE, 0)
                    val genderKnown = dataMap.getBoolean(DataLayerPaths.KEY_DS_USER_GENDER_KNOWN, false)
                    val userIsMale = if (genderKnown) dataMap.getBoolean(DataLayerPaths.KEY_DS_USER_IS_MALE, true) else null
                    Log.i(TAG, "收到手机下发的 DeepSeek 设置，应用中...")
                    dsSettings.applyFromRemote(dsKey, defaultModel, defaultThinking, userAge, userIsMale)
                    syncEvents.tryEmit("DeepSeek 设置已从手机同步")
                } catch (e: Exception) {
                    Log.e(TAG, "解析 DeepSeek 设置数据失败", e)
                }
            }

            // Tavily 设置下发（路径 /tavily_settings）
            if (path.startsWith(DataLayerPaths.PATH_TAVILY_SETTINGS)) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val tvKey = dataMap.getString(DataLayerPaths.KEY_TAVILY_API_KEY, "")
                        .takeIf { it.isNotBlank() }
                    Log.i(TAG, "收到手机下发的 Tavily 设置，应用中...")
                    tavilySettings.applyFromRemote(tvKey)
                    syncEvents.tryEmit("Tavily 设置已从手机同步")
                } catch (e: Exception) {
                    Log.e(TAG, "解析 Tavily 设置数据失败", e)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.i(TAG, "onMessageReceived: path=$path")

        when {
            path == DataLayerPaths.PATH_SYNC_REQUEST -> {
                Log.i(TAG, "收到手机的同步请求，开始提交未传送数据")
                serviceScope.launch { syncAllUnsynced() }
            }
            path.startsWith("${DataLayerPaths.PATH_ECG_ACK}/") -> {
                val timestamp = path.substringAfterLast('/').toLongOrNull()
                if (timestamp == null) {
                    Log.w(TAG, "收到无效 ECG ACK: $path")
                    return
                }
                // Mark only an exact timestamp that still exists in local history.
                if (historyRepo.markSynced(timestamp)) {
                    syncEvents.tryEmit("手机已确认保存 ECG")
                    Log.i(TAG, "手机已确认保存记录: $timestamp")
                } else {
                    Log.w(TAG, "忽略不匹配本地记录的 ECG ACK")
                }
            }
        }
    }

    /**
     * 同步所有未传送到手机的记录
     *
     * 在收到 /sync_request 消息时调用。
     * 即使 App 不在前台也能工作（Service 有独立的生命周期）。
     */
    private suspend fun syncAllUnsynced() {
        val unsynced = historyRepo.getUnsynced()
        if (unsynced.isEmpty()) {
            Log.i(TAG, "没有未传送的记录")
            return
        }

        Log.i(TAG, "开始同步 ${unsynced.size} 条未传送记录")
        var success = 0
        var failed = 0

        for (item in unsynced) {
            try {
                val transfer = EcgMeasurementTransfer(
                    timestamp = item.timestamp,
                    diagnosis = item.diagnosis,
                    possibleDiagnoses = item.possibleDiagnoses,
                    isReverse = item.isReverse,
                    avgHeartRate = item.avgHeartRate,
                    minHeartRate = item.minHeartRate,
                    maxHeartRate = item.maxHeartRate,
                    signalQuality = item.signalQuality,
                    isAbnormal = item.isAbnormal,
                    avgQrs = item.avgQrs,
                    avgP = item.avgP,
                    prInterval = item.prInterval,
                    avgQt = item.avgQt,
                    avgQtc = item.avgQtc,
                    pacCount = item.pacCount,
                    pvcCount = item.pvcCount,
                    rawEcgData = item.rawEcgData,
                    downsampledEcg = item.ecgSamples,
                    sampleRate = 500,
                    analysisMethod = item.analysisMethod,
                    aiReport = item.aiReport,
                    tavilyStatus = item.tavilyStatus,
                    ppgReferenceHr = item.ppgReferenceHr,
                    processedByAlgorithm = item.analysisMethod != "ds_raw",
                )

                val putDataMapReq = PutDataMapRequest.create(
                    "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}"
                )
                putDataMapReq.dataMap.putAll(MeasurementSerializer.toDataMap(transfer))
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()

                Tasks.await(Wearable.getDataClient(this).putDataItem(putDataReq))
                // DataItem acceptance is not phone persistence. Wait for /ecg_ack/{timestamp}.
                success++
                Log.i(TAG, "记录 ${item.timestamp} 已提交，等待手机 ACK")
            } catch (e: Exception) {
                Log.e(TAG, "传送记录 ${item.timestamp} 失败", e)
                failed++
            }
        }

        Log.i(TAG, "同步提交完成: 已提交 $success 条，失败 $failed 条")
        syncEvents.tryEmit(
            if (failed == 0) "已提交 $success 条，等待手机确认" else "已提交 $success 条，失败 $failed 条"
        )
    }

    companion object {
        private const val TAG = "WatchWearableListener"

        /**
         * One-shot real sync events. SharedFlow has no initial value, so reopening the app
         * cannot fabricate a success message while the phone is offline.
         */
        val syncEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    }
}
