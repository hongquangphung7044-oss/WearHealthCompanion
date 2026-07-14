package com.wearhealth.companion.mobile.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearhealth.companion.mobile.data.AppDatabase
import com.wearhealth.companion.mobile.data.MeasurementRepository
import com.wearhealth.companion.mobile.data.MobileApiKeyStore
import com.wearhealth.companion.shared.BleMeasurementCodec
import com.wearhealth.companion.shared.BleSyncProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phone-side BLE GATT peripheral for direct ECG synchronization.
 *
 * This does not use Google Play services. It advertises only while the phone synchronizer process
 * is alive, accepts one framed ECG transfer at a time, persists it through Room, and sends an ACK
 * indication only after that persistence succeeds.
 */
class BleSyncServer(private val context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var activeDevice: BluetoothDevice? = null
    private var transfer: IncomingTransfer? = null

    private val _status = MutableStateFlow("BLE 同步器未启动")
    val status: StateFlow<String> = _status.asStateFlow()

    private data class IncomingTransfer(
        val totalBytes: Int,
        val expectedCrc: Long,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(),
        var nextSequence: Int = 0,
    )

    /** Start the local GATT server and advertiser. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermissions()) {
            _status.value = "BLE 同步需要“附近设备”权限"
            return
        }
        val adapter = manager?.adapter
        if (adapter?.isEnabled != true) {
            _status.value = "请开启手机蓝牙以等待手表同步"
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            _status.value = "此手机不支持 BLE 外设广播"
            return
        }
        if (server != null && advertiser != null) return

        val gattServer = manager.openGattServer(context, callback)
        if (gattServer == null) {
            _status.value = "无法启动 BLE 同步服务"
            return
        }

        val upload = BluetoothGattCharacteristic(
            BleSyncProtocol.UPLOAD_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val ack = BluetoothGattCharacteristic(
            BleSyncProtocol.ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleSyncProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        val apiKey = BluetoothGattCharacteristic(
            BleSyncProtocol.API_KEY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        val service = BluetoothGattService(
            BleSyncProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(upload)
            addCharacteristic(ack)
            addCharacteristic(apiKey)
        }
        val bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            gattServer.close()
            _status.value = "此手机没有可用的 BLE 广播器"
            return
        }

        server = gattServer
        advertiser = bleAdvertiser
        ackCharacteristic = ack
        // GATT service registration is asynchronous. Start advertising only from
        // onServiceAdded so a watch can never connect before the characteristics exist.
        if (!gattServer.addService(service)) {
            stop()
            _status.value = "无法注册 BLE ECG 服务"
        } else {
            _status.value = "正在启动 BLE 同步器…"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        server?.close()
        server = null
        ackCharacteristic = null
        activeDevice = null
        transfer = null
        _status.value = "BLE 同步器已停止"
    }

    @SuppressLint("MissingPermission")
    private fun beginAdvertising() {
        val bleAdvertiser = advertiser ?: return
        bleAdvertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build(),
            AdvertiseData.Builder()
                .addServiceUuid(android.os.ParcelUuid(BleSyncProtocol.SERVICE_UUID))
                .setIncludeDeviceName(false)
                .build(),
            advertiseCallback,
        )
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != BleSyncProtocol.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _status.value = "正在启动 BLE 广播…"
                beginAdvertising()
            } else {
                stop()
                _status.value = "BLE ECG 服务注册失败（$status）"
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    if (device.address == activeDevice?.address) {
                        clearTransfer()
                        activeDevice = null
                    }
                    _status.value = "BLE 连接异常（$status），可重试"
                }
                newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        server?.cancelConnection(device)
                        _status.value = "拒绝未配对的 BLE 设备（bondState=${device.bondState}）"
                    } else if (activeDevice == null || activeDevice?.address == device.address) {
                        activeDevice = device
                        _status.value = "手表已通过 BLE 连接，等待 ECG 数据"
                    } else {
                        server?.cancelConnection(device)
                    }
                }
                newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device.address == activeDevice?.address) {
                        clearTransfer()
                        activeDevice = null
                        _status.value = "BLE 同步器就绪，等待手表传送"
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val authorized = device.bondState == BluetoothDevice.BOND_BONDED &&
                device.address == activeDevice?.address &&
                characteristic.uuid == BleSyncProtocol.API_KEY_UUID
            val bytes = if (authorized) MobileApiKeyStore(context).get().toByteArray(Charsets.UTF_8) else ByteArray(0)
            val validOffset = offset in 0..bytes.size
            server?.sendResponse(
                device,
                requestId,
                if (authorized && validOffset) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                offset,
                if (authorized && validOffset) bytes.copyOfRange(offset, bytes.size) else null,
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = !preparedWrite && offset == 0 &&
                device.bondState == BluetoothDevice.BOND_BONDED &&
                device.address == activeDevice?.address &&
                descriptor.characteristic.uuid == BleSyncProtocol.ACK_UUID &&
                descriptor.uuid == BleSyncProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            if (valid) _status.value = "BLE 通道已就绪，正在等待 ECG 数据"
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val accepted = !preparedWrite && offset == 0 &&
                characteristic.uuid == BleSyncProtocol.UPLOAD_UUID &&
                device.address == activeDevice?.address && receiveFrame(value)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
        }
    }

    private fun receiveFrame(frame: ByteArray): Boolean {
        return try {
            if (frame.isEmpty()) return false
            when (frame[0]) {
                BleSyncProtocol.TYPE_BEGIN -> receiveBegin(frame)
                BleSyncProtocol.TYPE_CHUNK -> receiveChunk(frame)
                BleSyncProtocol.TYPE_END -> receiveEnd(frame)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "无效 BLE ECG 帧", e)
            clearTransfer()
            false
        }
    }

    private fun receiveBegin(frame: ByteArray): Boolean {
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() != 1 + 1 + Int.SIZE_BYTES + Long.SIZE_BYTES) return false
        if (buffer.get() != BleSyncProtocol.TYPE_BEGIN || buffer.get() != BleSyncProtocol.VERSION) return false
        val totalBytes = buffer.int
        val crc = buffer.long
        if (totalBytes !in 1..BleSyncProtocol.MAX_TRANSFER_BYTES) return false
        transfer = IncomingTransfer(totalBytes = totalBytes, expectedCrc = crc)
        _status.value = "正在通过 BLE 接收 ECG 数据…"
        return true
    }

    private fun receiveChunk(frame: ByteArray): Boolean {
        val current = transfer ?: return false
        if (frame.size <= 1 + Int.SIZE_BYTES) return false
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get() != BleSyncProtocol.TYPE_CHUNK) return false
        if (buffer.int != current.nextSequence) return false
        val payload = ByteArray(buffer.remaining()).also(buffer::get)
        if (current.output.size() + payload.size > current.totalBytes) return false
        current.output.write(payload)
        current.nextSequence++
        return true
    }

    private fun receiveEnd(frame: ByteArray): Boolean {
        val current = transfer ?: return false
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() != 1 + Int.SIZE_BYTES || buffer.get() != BleSyncProtocol.TYPE_END) return false
        if (buffer.int != current.nextSequence || current.output.size() != current.totalBytes) return false

        val payload = current.output.toByteArray()
        if (BleSyncProtocol.crc32(payload) != current.expectedCrc) return false
        transfer = null // Reject overlapping END/chunk frames while persistence is in progress.
        _status.value = "正在保存 ECG 数据…"
        scope.launch {
            val device = activeDevice
            val timestamp = try {
                val measurement = BleMeasurementCodec.decode(payload)
                MeasurementRepository(AppDatabase.get(context).ecgMeasurementDao()).upsertByTimestamp(measurement)
                PhoneWearableListenerService.notifyMeasurementUpdated(context)
                measurement.timestamp
            } catch (e: Exception) {
                Log.e(TAG, "BLE ECG transfer failed before ACK", e)
                null
            }
            if (device != null && timestamp != null) {
                sendAck(device, success = true, timestamp = timestamp)
                _status.value = "ECG 已保存并确认给手表"
            } else if (device != null) {
                sendAck(device, success = false, timestamp = 0L)
                _status.value = "保存 ECG 失败，手表可重试"
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(device: BluetoothDevice, success: Boolean, timestamp: Long) {
        val characteristic = ackCharacteristic ?: return
        characteristic.value = BleSyncProtocol.ackFrame(success, timestamp)
        if (!server.orFalse { notifyCharacteristicChanged(device, characteristic, true) }) {
            Log.w(TAG, "无法发送 BLE ACK indication")
        }
    }

    private fun clearTransfer() {
        transfer = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _status.value = "BLE 同步器就绪，等待手表传送"
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE 广播启动失败: $errorCode")
            // Release the half-started GATT server so the retry button can really retry.
            stop()
            _status.value = "BLE 广播启动失败（$errorCode），请点刷新重试"
        }
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        true
    }

    private inline fun BluetoothGattServer?.orFalse(block: BluetoothGattServer.() -> Boolean): Boolean =
        this?.block() ?: false

    companion object {
        private const val TAG = "BleSyncServer"
    }
}
