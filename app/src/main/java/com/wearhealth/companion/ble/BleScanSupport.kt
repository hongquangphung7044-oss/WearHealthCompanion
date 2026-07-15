package com.wearhealth.companion.ble

import android.bluetooth.le.ScanResult
import com.wearhealth.companion.shared.BleAdvertisementMatcher
import com.wearhealth.companion.shared.BleSyncProtocol

/**
 * Software matching for the WearHealth BLE advertisement.
 *
 * Some Android/Samsung controllers silently drop results when a 128-bit UUID is placed in the
 * platform ScanFilter. The watch therefore scans with an unconstrained filter and validates the
 * advertised UUID here before it ever connects. Both Android's parsed UUID list and the raw AD
 * structures are checked because vendor Bluetooth stacks do not always populate both consistently.
 */
object BleScanSupport {
    fun matchesWearHealthService(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        if (record.serviceUuids?.any { it.uuid == BleSyncProtocol.SERVICE_UUID } == true) return true
        return BleAdvertisementMatcher.contains128BitServiceUuid(
            record.bytes,
            BleSyncProtocol.SERVICE_UUID,
        )
    }
}
