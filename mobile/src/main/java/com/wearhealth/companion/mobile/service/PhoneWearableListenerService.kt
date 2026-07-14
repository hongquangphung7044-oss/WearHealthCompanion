package com.wearhealth.companion.mobile.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearhealth.companion.mobile.data.AppDatabase
import com.wearhealth.companion.mobile.data.MeasurementRepository
import com.wearhealth.companion.shared.DataLayerPaths
import com.wearhealth.companion.shared.MeasurementSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手机端 Wearable Data Layer 监听服务
 *
 * 接收手表发来的 ECG 测量数据，反序列化后存入 Room 数据库，
 * 并发送广播通知 UI 刷新历史列表。
 *
 * 仅监听 [DataLayerPaths.PATH_ECG_MEASUREMENT] 路径前缀的数据变更
 * （已在 AndroidManifest 的 intent-filter 中配置 pathPrefix）。
 */
class PhoneWearableListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            val path = uri.path ?: continue
            // 仅处理 /ecg_measurement 开头的数据
            if (!path.startsWith(DataLayerPaths.PATH_ECG_MEASUREMENT)) continue
            // Only process new/changed data; deletion is not an incoming ECG measurement.
            if (event.type != DataEvent.TYPE_CHANGED) continue

            val sourceNodeId = uri.host
            if (sourceNodeId == null) {
                Log.w(TAG, "ECG 数据缺少来源节点: $path")
                continue
            }
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val transfer = try {
                MeasurementSerializer.fromDataMap(dataMap)
            } catch (e: Exception) {
                Log.e(TAG, "反序列化 ECG 数据失败", e)
                continue
            }

            scope.launch {
                val repo = MeasurementRepository(AppDatabase.get(applicationContext).ecgMeasurementDao())
                repo.insert(transfer)
                // ACK only after Room has accepted the complete, decoded record.
                Tasks.await(
                    Wearable.getMessageClient(this@PhoneWearableListenerService).sendMessage(
                        sourceNodeId,
                        "${DataLayerPaths.PATH_ECG_ACK}/${transfer.timestamp}",
                        ByteArray(0),
                    )
                )
                Log.i(TAG, "已保存并确认 ECG 记录: ts=${transfer.timestamp}, 诊断=${transfer.diagnosis}")
                notifyMeasurementUpdated(applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "PhoneWearableListener"
        /** 新测量记录到达的广播 Action（包内定向广播） */
        const val ACTION_ECG_UPDATED = "com.wearhealth.companion.mobile.ECG_UPDATED"

        /** Notify the UI after either Data Layer or direct BLE persistence succeeds. */
        fun notifyMeasurementUpdated(context: android.content.Context) {
            context.sendBroadcast(Intent(ACTION_ECG_UPDATED).setPackage(context.packageName))
        }
    }
}
