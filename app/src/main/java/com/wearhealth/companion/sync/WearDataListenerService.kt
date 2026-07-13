package com.wearhealth.companion.sync

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.security.ApiKeyStore

class WearDataListenerService : WearableListenerService() {
 override fun onDataChanged(events: DataEventBuffer) { events.forEach { event ->
  val path = event.dataItem.uri.path ?: return@forEach
  val map = DataMapItem.fromDataItem(event.dataItem).dataMap
  when {
   path.startsWith(WearEcgSync.ACK_PATH) -> map.getString("recordId")?.let { EcgHistoryRepository(this).markSynced(it) }
   path == WearEcgSync.CONFIG_PATH -> map.getString("apiKey")?.let { ApiKeyStore(this).save(it) }
  }
 }; events.release() }
}
