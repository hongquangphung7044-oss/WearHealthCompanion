package com.wearhealth.companion.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearhealth.companion.data.EcgRawArchive
import com.wearhealth.companion.data.HistoryItem
import java.util.UUID
import java.util.concurrent.TimeUnit

object WearEcgSync {
    private const val TAG = "WearEcgSync"
    const val RECORD_PATH = "/ecg/record/"
    const val ACK_PATH = "/ecg/ack/"
    const val CONFIG_PATH = "/ecg/config/api-key"
    fun send(context: Context, item: HistoryItem): Boolean = try {
        val raw = EcgRawArchive(context).readCompressed(item.recordId) ?: return false
        val request = PutDataMapRequest.create("$RECORD_PATH${item.recordId}/${UUID.randomUUID()}").apply {
            dataMap.putInt("protocol", 1); dataMap.putString("recordId", item.recordId); dataMap.putLong("timestamp", item.timestamp)
            dataMap.putStringArrayList("diagnosis", ArrayList(item.diagnosis)); dataMap.putInt("heartRate", item.avgHeartRate)
            dataMap.putInt("minHeartRate", item.minHeartRate); dataMap.putInt("maxHeartRate", item.maxHeartRate)
            dataMap.putDouble("quality", item.signalQuality); dataMap.putBoolean("abnormal", item.isAbnormal)
            dataMap.putInt("qrs", item.avgQrs); dataMap.putInt("pr", item.prInterval); dataMap.putInt("qt", item.avgQt); dataMap.putInt("qtc", item.avgQtc)
            dataMap.putInt("pac", item.pacCount); dataMap.putInt("pvc", item.pvcCount); dataMap.putInt("sampleRate", 500); dataMap.putInt("sampleCount", item.rawSampleCount)
            dataMap.putAsset("raw", Asset.createFromBytes(raw)); dataMap.putLong("nonce", System.nanoTime())
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(context).putDataItem(request), 20, TimeUnit.SECONDS)
        true
    } catch (e: Exception) { Log.w(TAG, "queued/failed", e); false }
}
