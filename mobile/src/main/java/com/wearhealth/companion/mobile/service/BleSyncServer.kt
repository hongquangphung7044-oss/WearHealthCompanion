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
import com.wearhealth.companion.mobile.data.MobileDeepSeekSettings
import com.wearhealth.companion.mobile.data.MobileTavilySettings
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.BleMeasurementCodec
import com.wearhealth.companion.shared.BleSyncProtocol
import com.wearhealth.companion.shared.DataLayerPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    private var ackSubscribedAddress: String? = null
    private var pendingAckAddress: String? = null
    private var pendingAckSuccess = false
    private var lastOutcome: String? = null
    private var transfer: IncomingTransfer? = null
    private var persistenceJob: Job? = null

    private val _status = MutableStateFlow("BLE 监听未启动")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

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
        val dsSettings = BluetoothGattCharacteristic(
            BleSyncProtocol.DS_SETTINGS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        val tavilySettings = BluetoothGattCharacteristic(
            BleSyncProtocol.TAVILY_SETTINGS_UUID,
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
            addCharacteristic(dsSettings)
            addCharacteristic(tavilySettings)
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
        ackSubscribedAddress = null
        pendingAckAddress = null
        pendingAckSuccess = false
        lastOutcome = null
        transfer = null
        persistenceJob?.cancel()
        persistenceJob = null
        _connected.value = false
        _status.value = "BLE 监听已停止；点“重新连接”恢复等待"
    }

    @SuppressLint("MissingPermission")
    private fun beginAdvertising() {
        val bleAdvertiser = advertiser ?: return
        bleAdvertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
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
                        clearConnectionState()
                    }
                    _status.value = "BLE 已断开（异常 $status）；手机仍在监听，可在手表重试"
                }
                newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    if (device.bondState != BluetoothDevice.BOND_BONDED) {
                        server?.cancelConnection(device)
                        _status.value = "拒绝未配对的 BLE 设备（bondState=${device.bondState}）"
                    } else if (activeDevice == null || activeDevice?.address == device.address) {
                        activeDevice = device
                        ackSubscribedAddress = null
                        lastOutcome = null
                        _connected.value = true
                        _status.value = "BLE 已连接到手表；正在建立 ECG 通道…"
                    } else {
                        server?.cancelConnection(device)
                    }
                }
                newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device.address == activeDevice?.address) {
                        clearConnectionState()
                        _status.value = lastOutcome
                            ?.let { "$it；当前 BLE 已断开并继续监听" }
                            ?: "BLE 待机：当前未连接；正在监听手表主动连接"
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
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED &&
                device.address == activeDevice?.address
            // 按特征值 UUID 分流：HeartVoice API Key / DeepSeek 设置 JSON / Tavily 设置 JSON
            val bytes = if (bonded) {
                when (characteristic.uuid) {
                    BleSyncProtocol.API_KEY_UUID ->
                        ApiKeyValidator.normalizeApiKey(MobileApiKeyStore(context).get())
                            .getOrNull()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    BleSyncProtocol.DS_SETTINGS_UUID ->
                        buildDsSettingsJson().toByteArray(Charsets.UTF_8)
                    BleSyncProtocol.TAVILY_SETTINGS_UUID ->
                        buildTavilySettingsJson().toByteArray(Charsets.UTF_8)
                    else -> ByteArray(0)
                }
            } else ByteArray(0)
            val validOffset = offset in 0..bytes.size
            server?.sendResponse(
                device,
                requestId,
                if (bonded && validOffset) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                offset,
                if (bonded && validOffset) bytes.copyOfRange(offset, bytes.size) else null,
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
            val authorized = !preparedWrite && offset == 0 &&
                device.bondState == BluetoothDevice.BOND_BONDED &&
                device.address == activeDevice?.address &&
                descriptor.characteristic.uuid == BleSyncProtocol.ACK_UUID &&
                descriptor.uuid == BleSyncProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID
            val enabling = authorized && value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val disabling = authorized && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val valid = enabling || disabling
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            when {
                enabling -> {
                    ackSubscribedAddress = device.address
                    _status.value = "BLE 已连接，ECG 与 ACK 通道就绪；等待手表分片"
                }
                disabling -> {
                    ackSubscribedAddress = null
                    _status.value = "BLE 已连接，但手表已关闭 ACK 通道"
                }
            }
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
                device.address == activeDevice?.address &&
                device.address == ackSubscribedAddress &&
                receiveFrame(value)
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

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (device.address != pendingAckAddress) return
            val wasSuccessAck = pendingAckSuccess
            pendingAckAddress = null
            pendingAckSuccess = false
            if (status == BluetoothGatt.GATT_SUCCESS && wasSuccessAck) {
                lastOutcome = "ECG 已保存，ACK 已送达手表"
                _status.value = lastOutcome!!
            } else if (status == BluetoothGatt.GATT_SUCCESS) {
                _status.value = "保存失败信息已送达手表；可重试"
            } else {
                lastOutcome = if (wasSuccessAck) {
                    "Room 已保存，但 ACK 发送失败（GATT $status）；可重传且不会重复"
                } else {
                    "保存失败且 ACK 发送失败（GATT $status）；请重试"
                }
                _status.value = lastOutcome!!
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
        lastOutcome = null
        _status.value = "已收到 ECG BEGIN；准备接收 $totalBytes 字节"
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
        val percent = (current.output.size().toLong() * 100L / current.totalBytes).coerceIn(0L, 100L)
        if (current.nextSequence == 1 || current.nextSequence % 25 == 0 || percent == 100L) {
            _status.value = "正在接收 ECG 分片 #${current.nextSequence}：$percent%（${current.output.size()}/${current.totalBytes} 字节）"
        }
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
        persistenceJob?.cancel()
        persistenceJob = scope.launch {
            val device = activeDevice
            val timestamp = try {
                val measurement = BleMeasurementCodec.decode(payload)
                MeasurementRepository(AppDatabase.get(context).ecgMeasurementDao()).upsertByTimestamp(measurement)
                PhoneWearableListenerService.notifyMeasurementUpdated(context)
                measurement.timestamp
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "BLE ECG transfer failed before ACK", e)
                null
            }
            if (device != null && timestamp != null) {
                if (!sendAck(device, success = true, timestamp = timestamp)) {
                    lastOutcome = "Room 已保存，但 ACK 未能发出；可重传且不会重复"
                    _status.value = lastOutcome!!
                }
            } else if (device != null) {
                if (!sendAck(device, success = false, timestamp = 0L)) {
                    _status.value = "保存 ECG 失败且错误 ACK 未能发出；请在手表重试"
                }
            }
            persistenceJob = null
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(device: BluetoothDevice, success: Boolean, timestamp: Long): Boolean {
        val characteristic = ackCharacteristic ?: return false
        if (device.address != activeDevice?.address || device.address != ackSubscribedAddress) return false
        characteristic.value = BleSyncProtocol.ackFrame(success, timestamp)
        pendingAckAddress = device.address
        pendingAckSuccess = success
        _status.value = if (success) {
            "Room 已保存 ECG；正在等待 ACK indication 送达手表…"
        } else {
            "保存 ECG 失败；正在把失败 ACK 送达手表…"
        }
        val queued = server.orFalse { notifyCharacteristicChanged(device, characteristic, true) }
        if (!queued) {
            pendingAckAddress = null
            pendingAckSuccess = false
            Log.w(TAG, "无法发送 BLE ACK indication")
        }
        return queued
    }

    private fun clearTransfer() {
        transfer = null
    }

    private fun clearConnectionState() {
        clearTransfer()
        activeDevice = null
        ackSubscribedAddress = null
        pendingAckAddress = null
        pendingAckSuccess = false
        _connected.value = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _status.value = "BLE 待机：当前未连接；正在监听手表主动连接"
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

    /**
     * 构造 DeepSeek 设置 JSON（手表通过 BLE 拉取）。
     * 字段名与 [com.wearhealth.companion.shared.DataLayerPaths] 的 DS 设置 key 一致，
     * 手表端用同一套 applyFromRemote 解析逻辑。
     */
    private fun buildDsSettingsJson(): String {
        val ds = MobileDeepSeekSettings(context)
        val json = JSONObject()
        // API Key 走归一化校验，避免把脏 Key 发给手表
        ApiKeyValidator.normalizeApiKey(ds.getApiKey()).getOrNull()?.let {
            json.put(DataLayerPaths.KEY_DS_API_KEY, it)
        }
        json.put(DataLayerPaths.KEY_DS_DEFAULT_MODEL, ds.getDefaultModel().name)
        json.put(DataLayerPaths.KEY_DS_DEFAULT_THINKING, ds.getDefaultThinking().name)
        json.put(DataLayerPaths.KEY_DS_USER_AGE, ds.getUserAge())
        val isMale = ds.getUserIsMale()
        if (isMale != null) {
            json.put(DataLayerPaths.KEY_DS_USER_GENDER_KNOWN, true)
            json.put(DataLayerPaths.KEY_DS_USER_IS_MALE, isMale)
        } else {
            json.put(DataLayerPaths.KEY_DS_USER_GENDER_KNOWN, false)
        }
        return json.toString()
    }

    /**
     * 构造 Tavily 设置 JSON（手表通过 BLE 拉取）。
     * 字段名与 [com.wearhealth.companion.shared.DataLayerPaths] 的 Tavily key 一致。
     * 仅一个字段：API Key（走归一化校验，避免脏 Key 发给手表）。
     */
    private fun buildTavilySettingsJson(): String {
        val tv = MobileTavilySettings(context)
        val json = JSONObject()
        ApiKeyValidator.normalizeApiKey(tv.getApiKey()).getOrNull()?.let {
            json.put(DataLayerPaths.KEY_TAVILY_API_KEY, it)
        }
        return json.toString()
    }

    companion object {
        private const val TAG = "BleSyncServer"
    }
}
