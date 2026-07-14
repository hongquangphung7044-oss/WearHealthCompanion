package com.wearhealth.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import java.util.ArrayDeque

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
    private val phoneStore = BlePhoneDeviceStore(context)

    private var scanner: BluetoothLeScanner? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var uploadCharacteristic: BluetoothGattCharacteristic? = null
    private var transferPayload: ByteArray? = null
    private var frames: List<ByteArray> = emptyList()
    private var nextFrame = 0
    private var expectedTimestamp = 0L
    private var completion: ((BleUploadResult) -> Unit)? = null
    private var terminal = false
    private var observedResults = 0
    private var observedTarget = false
    private var observedUnbondedState: Int? = null
    private var targetConnectionStarted = false
    private var probedBondedCandidates = 0
    private val bondedCandidates = ArrayDeque<BluetoothDevice>()
    private var tryingBondedCandidate = false
    private var matchedService = false

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
        observedResults = 0
        observedTarget = false
        observedUnbondedState = null
        probedBondedCandidates = 0
        targetConnectionStarted = false
        tryingBondedCandidate = false
        matchedService = false
        this.adapter = adapter
        this.scanner = scanner
        bondedCandidates.clear()

        val cachedPhone = phoneStore.getBonded(adapter)
        if (cachedPhone != null) bondedCandidates.add(cachedPhone)
        val remainingCandidates = try {
            adapter.bondedDevices
                .asSequence()
                .filter { cachedPhone == null || it.address != cachedPhone.address }
                .sortedByDescending {
                    if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE) 1 else 0
                }
                .take((MAX_BONDED_CANDIDATES - bondedCandidates.size).coerceAtLeast(0))
                .toList()
        } catch (e: SecurityException) {
            finish(BleUploadResult.Failure("系统不允许读取已配对设备；请重新授予附近设备权限"))
            return
        }
        bondedCandidates.addAll(remainingCandidates)

        handler.postDelayed(timeout, TIMEOUT_MS)
        if (bondedCandidates.isEmpty()) startScan() else connectNextBondedCandidate()
    }

    @SuppressLint("MissingPermission")
    private fun connectNextBondedCandidate() {
        if (terminal) return
        handler.removeCallbacks(bondedCandidateTimeout)
        if (tryingBondedCandidate) {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
        }
        tryingBondedCandidate = false
        matchedService = false
        targetConnectionStarted = false

        val device = bondedCandidates.pollFirst()
        if (device == null) {
            startScan()
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            connectNextBondedCandidate()
            return
        }

        tryingBondedCandidate = true
        targetConnectionStarted = true
        probedBondedCandidates++
        val connected = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        if (connected == null) {
            connectNextBondedCandidate()
        } else {
            gatt = connected
            handler.postDelayed(bondedCandidateTimeout, BONDED_CANDIDATE_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (terminal) return
        handler.removeCallbacks(bondedCandidateTimeout)
        if (tryingBondedCandidate) {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
        }
        tryingBondedCandidate = false
        matchedService = false
        targetConnectionStarted = false

        val scanner = scanner ?: run {
            finish(BleUploadResult.Failure("BLE 扫描不可用"))
            return
        }
        try {
            // Samsung/Wear OS may silently lose 128-bit UUID hardware-filter results. Scan with
            // an empty platform filter and validate the service UUID in BleScanSupport instead.
            scanner.startScan(
                listOf(ScanFilter.Builder().build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build(),
                scanCallback,
            )
        } catch (e: Exception) {
            finish(BleUploadResult.Failure("无法扫描手机同步器: ${e.message ?: "未知错误"}"))
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        private fun handleScanResult(result: ScanResult) {
            if (terminal) return
            observedResults++
            if (!BleScanSupport.matchesWearHealthService(result)) return
            observedTarget = true
            if (targetConnectionStarted) return
            if (result.device.bondState != BluetoothDevice.BOND_BONDED) {
                observedUnbondedState = result.device.bondState
                Log.w(TAG, "发现同步器广播，但系统报告未配对（bondState=${result.device.bondState}）")
                return
            }
            targetConnectionStarted = true
            scanner?.stopScan(this)
            scanner = null
            val connected = result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
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
            if (gatt !== this@BleEcgUploader.gatt) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                if (tryingBondedCandidate && !matchedService) {
                    connectNextBondedCandidate()
                } else {
                    finish(BleUploadResult.Failure("手机 BLE 连接失败（$status）"))
                }
                return
            }
            this@BleEcgUploader.gatt = gatt
            if (!gatt.requestMtu(BleSyncProtocol.PREFERRED_MTU)) {
                if (tryingBondedCandidate && !matchedService) connectNextBondedCandidate()
                else finish(BleUploadResult.Failure("无法协商 ECG BLE 所需的数据包大小"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (terminal || gatt !== this@BleEcgUploader.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < BleSyncProtocol.MIN_ECG_MTU) {
                if (tryingBondedCandidate && !matchedService) connectNextBondedCandidate()
                else finish(BleUploadResult.Failure("手机 BLE MTU 不足，无法可靠传送完整 ECG"))
                return
            }
            buildFramesForMtu(mtu)
            if (!gatt.discoverServices()) {
                if (tryingBondedCandidate && !matchedService) connectNextBondedCandidate()
                else finish(BleUploadResult.Failure("无法发现手机 BLE 服务"))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (terminal || gatt !== this@BleEcgUploader.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (tryingBondedCandidate && !matchedService) connectNextBondedCandidate()
                else finish(BleUploadResult.Failure("无法发现手机 BLE 服务（$status）"))
                return
            }
            val service = gatt.getService(BleSyncProtocol.SERVICE_UUID)
            val upload = service?.getCharacteristic(BleSyncProtocol.UPLOAD_UUID)
            val ack = service?.getCharacteristic(BleSyncProtocol.ACK_UUID)
            if (upload == null || ack == null) {
                if (tryingBondedCandidate) {
                    connectNextBondedCandidate()
                } else {
                    finish(BleUploadResult.Failure("手机不是兼容的 ECG 同步器"))
                }
                return
            }
            matchedService = true
            handler.removeCallbacks(bondedCandidateTimeout)
            phoneStore.save(gatt.device)
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
            if (terminal || gatt !== this@BleEcgUploader.gatt) return
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
            if (terminal || gatt !== this@BleEcgUploader.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(BleUploadResult.Failure("ECG BLE 分片发送失败（$status）"))
                return
            }
            writeNextFrame()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (terminal || gatt !== this@BleEcgUploader.gatt ||
                characteristic.uuid != BleSyncProtocol.ACK_UUID) return
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

    private val bondedCandidateTimeout = Runnable {
        if (!terminal && tryingBondedCandidate && !matchedService) {
            connectNextBondedCandidate()
        }
    }

    private val timeout = Runnable {
        val message = when {
            observedUnbondedState != null ->
                "已发现手机同步器，但系统报告未配对（bondState=$observedUnbondedState）"
            observedTarget ->
                "已发现手机同步器广播，但未能建立安全连接"
            observedResults > 0 ->
                "已直连检查 $probedBondedCandidates 个系统配对设备；手表扫描正常（看到 $observedResults 个周边广播），但未看到手机同步器"
            probedBondedCandidates > 0 ->
                "已直连检查 $probedBondedCandidates 个系统配对设备但未找到本项目服务，且手表未收到 BLE 广播；请保持手机 App 前台并重启 BLE 同步器"
            else ->
                "系统没有可直连的配对设备，且手表未收到 BLE 广播；请检查 Galaxy Wearable 配对和附近设备权限"
        }
        finish(BleUploadResult.Failure(message))
    }

    @SuppressLint("MissingPermission")
    private fun finish(result: BleUploadResult) {
        if (terminal) return
        terminal = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(bondedCandidateTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        adapter = null
        scanner = null
        bondedCandidates.clear()
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
        private const val BONDED_CANDIDATE_TIMEOUT_MS = 5_000L
        private const val MAX_BONDED_CANDIDATES = 6
        private const val TAG = "BleEcgUploader"
    }
}

sealed interface BleUploadResult {
    data object Success : BleUploadResult
    data class Failure(val message: String) : BleUploadResult
}
