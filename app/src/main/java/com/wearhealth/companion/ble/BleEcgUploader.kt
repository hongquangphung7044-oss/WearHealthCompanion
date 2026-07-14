package com.wearhealth.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearhealth.companion.shared.BleMeasurementCodec
import com.wearhealth.companion.shared.BleSyncProtocol
import com.wearhealth.companion.shared.EcgMeasurementTransfer

/**
 * Watch-side direct BLE uploader.
 *
 * It finds the phone synchronizer's advertised GATT service, subscribes to persistence ACK
 * indications, sends a checksummed and sequenced payload, then succeeds only after the phone
 * confirms that Room accepted the measurement.
 */
class BleEcgUploader(private val context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var uploadCharacteristic: BluetoothGattCharacteristic? = null
    private var transferPayload: ByteArray? = null
    private var frames: List<ByteArray> = emptyList()
    private var nextFrame = 0
    private var expectedTimestamp = 0L
    private var completion: ((BleUploadResult) -> Unit)? = null
    private var terminal = false

    fun upload(transfer: EcgMeasurementTransfer, done: (BleUploadResult) -> Unit) {
        if (completion != null) {
            done(BleUploadResult.Failure("BLE 传送已在进行中"))
            return
        }
        if (!hasPermissions()) {
            done(BleUploadResult.Failure("未授予附近设备权限"))
            return
        }
        val adapter = manager?.adapter
        if (adapter?.isEnabled != true) {
            done(BleUploadResult.Failure("手表蓝牙未开启"))
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            done(BleUploadResult.Failure("BLE 扫描不可用"))
            return
        }

        val payload = try {
            BleMeasurementCodec.encode(transfer)
        } catch (e: Exception) {
            done(BleUploadResult.Failure("ECG 数据编码失败: ${e.message ?: "未知错误"}"))
            return
        }
        transferPayload = payload
        expectedTimestamp = transfer.timestamp
        completion = done
        terminal = false
        nextFrame = 0
        this.scanner = scanner

        try {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(android.os.ParcelUuid(BleSyncProtocol.SERVICE_UUID))
                        .build(),
                ),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            handler.postDelayed(timeout, TIMEOUT_MS)
        } catch (e: Exception) {
            finish(BleUploadResult.Failure("无法扫描手机同步器: ${e.message ?: "未知错误"}"))
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (terminal) return
            if (result.device.bondState != BluetoothDevice.BOND_BONDED) {
                Log.w(TAG, "忽略未与手表配对的 BLE 设备（bondState=${result.device.bondState}）")
                return
            }
            scanner?.stopScan(this)
            scanner = null
            val connected = result.device.connectGatt(context, false, gattCallback)
            if (connected == null) finish(BleUploadResult.Failure("无法连接手机同步器"))
            else gatt = connected
        }

        override fun onScanFailed(errorCode: Int) {
            finish(BleUploadResult.Failure("BLE 扫描失败（$errorCode）"))
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (terminal) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                finish(BleUploadResult.Failure("手机 BLE 连接失败（$status）"))
                return
            }
            this@BleEcgUploader.gatt = gatt
            if (!gatt.requestMtu(BleSyncProtocol.PREFERRED_MTU)) {
                finish(BleUploadResult.Failure("无法协商 ECG BLE 所需的数据包大小"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (terminal) return
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < BleSyncProtocol.MIN_ECG_MTU) {
                finish(BleUploadResult.Failure("手机 BLE MTU 不足，无法可靠传送完整 ECG"))
                return
            }
            buildFramesForMtu(mtu)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (terminal) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(BleUploadResult.Failure("无法发现手机 BLE 服务（$status）"))
                return
            }
            val service = gatt.getService(BleSyncProtocol.SERVICE_UUID)
            val upload = service?.getCharacteristic(BleSyncProtocol.UPLOAD_UUID)
            val ack = service?.getCharacteristic(BleSyncProtocol.ACK_UUID)
            if (upload == null || ack == null) {
                finish(BleUploadResult.Failure("手机不是兼容的 ECG 同步器"))
                return
            }
            uploadCharacteristic = upload
            if (!gatt.setCharacteristicNotification(ack, true)) {
                finish(BleUploadResult.Failure("无法订阅手机确认通知"))
                return
            }
            val descriptor = ack.getDescriptor(BleSyncProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                finish(BleUploadResult.Failure("手机同步器缺少确认通道"))
                return
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                finish(BleUploadResult.Failure("无法启用手机确认通道"))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (terminal) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(BleUploadResult.Failure("手机确认通道建立失败（$status）"))
                return
            }
            writeNextFrame()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (terminal) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(BleUploadResult.Failure("ECG BLE 分片发送失败（$status）"))
                return
            }
            writeNextFrame()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (terminal || characteristic.uuid != BleSyncProtocol.ACK_UUID) return
            val ack = BleSyncProtocol.parseAck(characteristic.value)
            if (ack == null) {
                finish(BleUploadResult.Failure("收到无效的手机确认"))
            } else if (ack.success && ack.timestamp == expectedTimestamp) {
                finish(BleUploadResult.Success)
            } else {
                finish(BleUploadResult.Failure("手机未能保存 ECG 数据"))
            }
        }
    }

    private fun buildFramesForMtu(mtu: Int) {
        val payload = transferPayload ?: return
        val chunkPayloadBytes = BleSyncProtocol.chunkPayloadBytesForMtu(mtu)
        if (chunkPayloadBytes <= 0) {
            finish(BleUploadResult.Failure("协商后的 BLE MTU 无法承载 ECG 分片"))
            return
        }
        val chunks = payload.asList().chunked(chunkPayloadBytes).map { it.toByteArray() }
        frames = buildList {
            add(BleSyncProtocol.beginFrame(payload))
            chunks.forEachIndexed { index, bytes -> add(BleSyncProtocol.chunkFrame(index, bytes)) }
            add(BleSyncProtocol.endFrame(chunks.size))
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextFrame() {
        if (terminal) return
        if (frames.isEmpty()) {
            finish(BleUploadResult.Failure("BLE 分片尚未完成初始化"))
            return
        }
        if (nextFrame >= frames.size) {
            // The END frame has been accepted by GATT. Wait for the Room persistence indication.
            return
        }
        val characteristic = uploadCharacteristic
        val currentGatt = gatt
        if (characteristic == null || currentGatt == null) {
            finish(BleUploadResult.Failure("BLE 连接已断开"))
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = frames[nextFrame]
        nextFrame++
        if (!currentGatt.writeCharacteristic(characteristic)) {
            finish(BleUploadResult.Failure("无法发送 ECG BLE 分片"))
        }
    }

    private val timeout = Runnable {
        finish(BleUploadResult.Failure("等待手机 BLE 同步器超时；请保持手机同步器打开"))
    }

    @SuppressLint("MissingPermission")
    private fun finish(result: BleUploadResult) {
        if (terminal) return
        terminal = true
        handler.removeCallbacks(timeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanner = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        uploadCharacteristic = null
        transferPayload = null
        frames = emptyList()
        nextFrame = 0
        val callback = completion
        completion = null
        callback?.invoke(result)
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        true
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L
        private const val TAG = "BleEcgUploader"
    }
}

sealed interface BleUploadResult {
    data object Success : BleUploadResult
    data class Failure(val message: String) : BleUploadResult
}
