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
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.shared.DataLayerPaths
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.shared.MeasurementSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 手表端 Wearable Data Layer 监听服务
 *
 * 职责：
 * 1. 接收手机端下发的 API Key（路径 /api_key）并保存到 ApiKeyManager
 * 2. 接收手机端的同步请求（路径 /sync_request），触发未传送数据的同步
 *
 * 通过 [ApiKeyManager.refreshTrigger] 通知 UI 刷新 API Key 状态。
 * 通过 [syncCompleted] 通知 UI 刷新历史列表（同步状态变化）。
 */
class WatchWearableListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val apiKeyManager by lazy { ApiKeyManager(this) }
    private val historyRepo by lazy { EcgHistoryRepository(this) }

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
                    val apiKey = dataMap.getString(DataLayerPaths.KEY_API_KEY, "")
                    if (apiKey.isNotBlank()) {
                        Log.i(TAG, "收到手机下发的 API Key，保存中...")
                        apiKeyManager.saveApiKey(apiKey)
                        // ApiKeyManager.saveApiKey 会触发 refreshTrigger，通知 UI 刷新
                    } else {
                        Log.w(TAG, "收到的 API Key 为空")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 API Key 数据失败", e)
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        Log.i(TAG, "onMessageReceived: path=$path")

        if (path == DataLayerPaths.PATH_SYNC_REQUEST) {
            Log.i(TAG, "收到手机的同步请求，开始同步未传送数据")
            serviceScope.launch {
                syncAllUnsynced()
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
                    avgHeartRate = item.avgHeartRate,
                    minHeartRate = item.minHeartRate,
                    maxHeartRate = item.maxHeartRate,
                    signalQuality = item.signalQuality,
                    isAbnormal = item.isAbnormal,
                    avgQrs = item.avgQrs,
                    prInterval = item.prInterval,
                    avgQt = item.avgQt,
                    avgQtc = item.avgQtc,
                    pacCount = item.pacCount,
                    pvcCount = item.pvcCount,
                    rawEcgData = item.rawEcgData,
                    downsampledEcg = item.ecgSamples,
                    sampleRate = 500,
                )

                val putDataMapReq = PutDataMapRequest.create(
                    "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}"
                )
                putDataMapReq.dataMap.putAll(MeasurementSerializer.toDataMap(transfer))
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()

                Tasks.await(Wearable.getDataClient(this).putDataItem(putDataReq))
                historyRepo.markSynced(item.timestamp)
                success++
                Log.i(TAG, "记录 ${item.timestamp} 传送成功")
            } catch (e: Exception) {
                Log.e(TAG, "传送记录 ${item.timestamp} 失败", e)
                failed++
            }
        }

        Log.i(TAG, "同步完成: 成功 $success 条，失败 $failed 条")
        // 通知 UI 刷新历史列表（如果 App 在前台）
        syncCompleted.value = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "WatchWearableListener"

        /**
         * 同步完成事件流。
         *
         * 当 Service 完成数据同步后更新此值，ViewModel 可观察此 flow 来刷新历史列表。
         */
        val syncCompleted: MutableStateFlow<Long> = MutableStateFlow(0L)
    }
}
