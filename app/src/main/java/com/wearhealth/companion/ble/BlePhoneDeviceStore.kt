package com.wearhealth.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context

/** Stores only the bonded phone's Bluetooth address, never an API key or other secret. */
class BlePhoneDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    fun save(device: BluetoothDevice) {
        prefs.edit().putString(KEY_ADDRESS, device.address).apply()
    }

    @SuppressLint("MissingPermission")
    fun getBonded(adapter: BluetoothAdapter): BluetoothDevice? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            clear()
            return null
        }
        return if (device.bondState == BluetoothDevice.BOND_BONDED) device else {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ADDRESS).apply()
    }

    companion object {
        private const val PREFS_NAME = "ble_phone_device"
        private const val KEY_ADDRESS = "bonded_address"
    }
}
