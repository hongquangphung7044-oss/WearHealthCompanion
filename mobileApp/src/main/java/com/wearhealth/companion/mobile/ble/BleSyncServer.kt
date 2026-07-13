package com.wearhealth.companion.mobile.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearhealth.companion.mobile.data.EcgRecord
import com.wearhealth.companion.mobile.data.EcgStore
import com.wearhealth.companion.mobile.settings.SecureSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/** Phone-side BLE peripheral. It is independent of Google Play services and Wear OS Data Layer. */
class BleSyncServer(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var transfer: Transfer? = null
    private data class Transfer(val id: String, val total: Int, val meta: JSONObject, val output: ByteArrayOutputStream = ByteArrayOutputStream())

    fun start(): Boolean {
        if (!hasBluetoothPermissions() || manager?.adapter?.isEnabled != true) return false
        if (server != null) return true
        val upload = BluetoothGattCharacteristic(BleProtocol.UPLOAD_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val api = BluetoothGattCharacteristic(BleProtocol.API_KEY_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        server = manager.openGattServer(context, callback)?.also { it.addService(BluetoothGattService(BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(upload); addCharacteristic(api) }) }
        advertiser = manager.adapter.bluetoothLeAdvertiser
        advertiser?.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID)).setIncludeDeviceName(false).build(), advertiseCallback)
        return server != null
    }

    fun stop() { advertiser?.stopAdvertising(advertiseCallback); advertiser = null; server?.close(); server = null; transfer = null }

    @SuppressLint("MissingPermission") private val callback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = if (characteristic.uuid == BleProtocol.API_KEY_UUID) SecureSettings(context).apiKey().toByteArray() else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val ok = characteristic.uuid == BleProtocol.UPLOAD_UUID && receive(value)
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    private fun receive(frame: ByteArray): Boolean = try {
        if (frame.isEmpty()) return false
        when (frame[0]) {
            BleProtocol.TYPE_BEGIN -> {
                val buffer = ByteBuffer.wrap(frame, 1, frame.size - 1)
                val idBytes = ByteArray(16).also(buffer::get); val total = buffer.int
                val meta = JSONObject(String(ByteArray(buffer.remaining()).also(buffer::get)))
                transfer = Transfer(BleProtocol.uuidFrom(idBytes), total, meta)
            }
            BleProtocol.TYPE_CHUNK -> transfer?.output?.write(frame, 1, frame.size - 1) ?: return false
            BleProtocol.TYPE_END -> persist() ?: return false
            else -> return false
        }; true
    } catch (e: Exception) { Log.e("BleSyncServer", "Bad transfer frame", e); false }

    private fun persist(): Boolean {
        val item = transfer ?: return false
        if (item.output.size() != item.total) return false
        EcgStore.rawFile(context, item.id).writeBytes(item.output.toByteArray())
        val m = item.meta
        runBlocking { EcgStore.db(context).ecgDao().upsert(EcgRecord(item.id, m.optLong("timestamp"), m.optString("diagnosis"), m.optInt("hr"), m.optInt("minHr"), m.optInt("maxHr"), m.optDouble("quality"), m.optBoolean("abnormal"), m.optInt("qrs"), m.optInt("pr"), m.optInt("qt"), m.optInt("qtc"), m.optInt("pac"), m.optInt("pvc"), 500, m.optInt("sampleCount"), "${item.id}.ecgz")) }
        transfer = null
        return true
    }
    private val advertiseCallback = object : AdvertiseCallback() {}
    private fun hasBluetoothPermissions() = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } else true
}
