package com.wearhealth.companion.mobile.sync

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearhealth.companion.mobile.data.EcgRecord
import com.wearhealth.companion.mobile.data.EcgStore
import kotlinx.coroutines.runBlocking

/** ACK is emitted only after the compressed raw file and its Room index are both durable. */
class WearSyncListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        try {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
                val path = event.dataItem.uri.path ?: return@forEach
                if (!path.startsWith(WearProtocol.RECORD_PATH)) return@forEach
                receiveRecord(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        } finally {
            events.release()
        }
    }

    private fun receiveRecord(map: com.google.android.gms.wearable.DataMap) {
        val recordId = map.getString("recordId") ?: return
        try {
            val asset = map.getAsset("raw") ?: error("Missing ECG asset")
            val result = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
            val output = EcgStore.rawFile(this, recordId)
            result.inputStream?.use { input -> output.outputStream().use(input::copyTo) }
                ?: error("Cannot read ECG asset")
            val sampleCount = map.getInt("sampleCount")
            check(sampleCount > 0 && output.length() > 0L) { "Invalid ECG data" }
            runBlocking {
                EcgStore.db(this@WearSyncListenerService).ecgDao().upsert(
                    EcgRecord(
                        recordId = recordId,
                        timestamp = map.getLong("timestamp"),
                        diagnosisCsv = map.getStringArrayList("diagnosis")?.joinToString(",") ?: "",
                        heartRate = map.getInt("heartRate"),
                        minHeartRate = map.getInt("minHeartRate"),
                        maxHeartRate = map.getInt("maxHeartRate"),
                        signalQuality = map.getDouble("quality"),
                        abnormal = map.getBoolean("abnormal"),
                        qrs = map.getInt("qrs"), pr = map.getInt("pr"), qt = map.getInt("qt"), qtc = map.getInt("qtc"),
                        pac = map.getInt("pac"), pvc = map.getInt("pvc"),
                        sampleRate = map.getInt("sampleRate"), sampleCount = sampleCount,
                        rawFileName = output.name,
                    )
                )
            }
            sendAck(recordId)
        } catch (error: Exception) {
            Log.e("WearSync", "ECG $recordId was not persisted; ACK withheld", error)
        }
    }

    private fun sendAck(recordId: String) {
        val request = PutDataMapRequest.create("${WearProtocol.ACK_PATH}$recordId/${System.nanoTime()}").apply {
            dataMap.putString("recordId", recordId)
            dataMap.putLong("nonce", System.nanoTime())
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(this).putDataItem(request))
    }
}
