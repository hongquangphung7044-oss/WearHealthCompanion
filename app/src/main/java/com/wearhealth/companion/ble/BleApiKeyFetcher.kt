package com.wearhealth.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import androidx.core.content.ContextCompat
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.BleSyncProtocol
import java.util.ArrayDeque

/** Fetches the HeartVoice API key from the paired phone without requiring an ECG history item. */
class BleApiKeyFetcher(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val phoneStore = BlePhoneDeviceStore(context)

    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var callback: ((Result<String>) -> Unit)? = null
    private var finished = false

    private val bondedCandidates = ArrayDeque<BluetoothDevice>()
    private var tryingBondedCandidate = false
    private var matchedService = false
    private var targetConnectionStarted = false
    private var observedUnbondedState: Int? = null
    private var observedResults = 0
    private var observedTarget = false
    private var probedBondedCandidates = 0

    @SuppressLint("MissingPermission")
    fun fetch(done: (Result<String>) -> Unit) {
        if (callback != null) {
            done(Result.failure(IllegalStateException("BLE Key 获取已在进行中")))
            return
        }
        if (!hasPermissions()) {
            done(Result.failure(IllegalStateException("未授予附近设备权限")))
            return
        }
        val adapter = manager?.adapter
        if (adapter?.isEnabled != true) {
            done(Result.failure(IllegalStateException("手表蓝牙不可用")))
            return
        }
        val scanner = adapter.bluetoothLeScanner

        callback = done
        finished = false
        this.adapter = adapter
        this.scanner = scanner
        bondedCandidates.clear()
        tryingBondedCandidate = false
        matchedService = false
        targetConnectionStarted = false
        observedUnbondedState = null
        observedResults = 0
        observedTarget = false
        probedBondedCandidates = 0

        // One UI Watch may suppress all third-party scan callbacks even though Nearby Devices is
        // granted. Query Android's already-paired devices first and probe their GATT services
        // directly. No unpaired device is ever added to this queue.
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
            finish(Result.failure(IllegalStateException("系统不允许读取已配对设备；请重新授予附近设备权限")))
            return
        }
        bondedCandidates.addAll(remainingCandidates)

        handler.postDelayed(overallTimeout, FETCH_TIMEOUT_MS)
        if (bondedCandidates.isEmpty()) startScan() else connectNextBondedCandidate()
    }

    @SuppressLint("MissingPermission")
    private fun connectNextBondedCandidate() {
        if (finished) return
        handler.removeCallbacks(candidateTimeout)
        closeGatt()
        matchedService = false
        targetConnectionStarted = false

        val device = bondedCandidates.pollFirst()
        if (device == null) {
            tryingBondedCandidate = false
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
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
        if (gatt == null) {
            connectNextBondedCandidate()
        } else {
            handler.postDelayed(candidateTimeout, BONDED_CANDIDATE_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (finished) return
        handler.removeCallbacks(candidateTimeout)
        closeGatt()
        tryingBondedCandidate = false
        matchedService = false
        targetConnectionStarted = false
        try {
            // Do not put the custom 128-bit UUID in Android's hardware ScanFilter. Several
            // Samsung controllers silently drop matching advertisements. An empty filter keeps
            // this a filtered API call while BleScanSupport performs strict software matching.
            scanner?.startScan(
                listOf(ScanFilter.Builder().build()),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build(),
                scanCallback,
            ) ?: run {
                finish(Result.failure(IllegalStateException("BLE 扫描器不可用")))
                return
            }
        } catch (e: Exception) {
            finish(Result.failure(e))
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
            if (finished) return
            observedResults++
            if (!BleScanSupport.matchesWearHealthService(result)) return
            observedTarget = true
            if (targetConnectionStarted) return
            if (result.device.bondState != BluetoothDevice.BOND_BONDED) {
                observedUnbondedState = result.device.bondState
                android.util.Log.w(TAG, "发现同步器广播，但系统报告未配对（bondState=${result.device.bondState}）")
                return
            }
            targetConnectionStarted = true
            scanner?.stopScan(this)
            gatt = result.device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
            if (gatt == null) finish(Result.failure(IllegalStateException("无法连接手机同步器")))
        }

        override fun onScanFailed(errorCode: Int) {
            finish(Result.failure(IllegalStateException("BLE 扫描失败（$errorCode）")))
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (finished) return
            if (gatt !== this@BleApiKeyFetcher.gatt) {
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                if (tryingBondedCandidate && !matchedService) {
                    connectNextBondedCandidate()
                } else {
                    finish(Result.failure(IllegalStateException("手机 BLE 连接失败（$status）")))
                }
                return
            }
            this@BleApiKeyFetcher.gatt = gatt
            if (!gatt.discoverServices()) {
                if (tryingBondedCandidate) connectNextBondedCandidate()
                else finish(Result.failure(IllegalStateException("无法发现手机 GATT 服务")))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (finished || gatt !== this@BleApiKeyFetcher.gatt) return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(BleSyncProtocol.SERVICE_UUID)
            } else null
            val characteristic = service?.getCharacteristic(BleSyncProtocol.API_KEY_UUID)
            if (characteristic == null) {
                if (tryingBondedCandidate) connectNextBondedCandidate()
                else finish(Result.failure(IllegalStateException("手机同步器没有可读取的 API Key")))
                return
            }
            matchedService = true
            handler.removeCallbacks(candidateTimeout)
            if (!gatt.readCharacteristic(characteristic)) {
                finish(Result.failure(IllegalStateException("无法请求读取手机 API Key")))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (finished || gatt !== this@BleApiKeyFetcher.gatt ||
                characteristic.uuid != BleSyncProtocol.API_KEY_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(Result.failure(IllegalStateException("手机拒绝读取 Key（GATT $status）；请确认系统配对与链路加密")))
                return
            }
            val keyResult = ApiKeyValidator.normalizeApiKeyBytes(characteristic.value ?: ByteArray(0))
            val key = keyResult.getOrNull()
            if (key == null) {
                finish(Result.failure(
                    IllegalStateException(keyResult.exceptionOrNull()?.message ?: "手机端 API Key 无效")
                ))
            } else {
                phoneStore.save(gatt.device)
                finish(Result.success(key))
            }
        }
    }

    private val candidateTimeout = Runnable {
        if (!finished && tryingBondedCandidate && !matchedService) connectNextBondedCandidate()
    }

    private val overallTimeout = Runnable {
        val state = observedUnbondedState
        val message = when {
            state != null ->
                "已发现手机同步器，但系统报告未配对（bondState=$state）；请检查 Galaxy Wearable 配对状态"
            observedTarget ->
                "已发现手机同步器广播，但未能建立安全连接"
            observedResults > 0 ->
                "已直连检查 $probedBondedCandidates 个系统配对设备；手表扫描正常（看到 $observedResults 个周边广播），但未看到手机同步器"
            probedBondedCandidates > 0 ->
                "已直连检查 $probedBondedCandidates 个系统配对设备但未找到本项目服务，且手表未收到 BLE 广播；请确认手机后台同步已开启（或保持 App 前台）并重启 BLE 同步器"
            else ->
                "系统没有可直连的配对设备，且手表未收到 BLE 广播；请检查 Galaxy Wearable 配对和附近设备权限"
        }
        finish(Result.failure(IllegalStateException(message)))
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun finish(result: Result<String>) {
        if (finished) return
        finished = true
        handler.removeCallbacks(candidateTimeout)
        handler.removeCallbacks(overallTimeout)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        closeGatt()
        adapter = null
        scanner = null
        bondedCandidates.clear()
        val done = callback
        callback = null
        done?.invoke(result)
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else true

    companion object {
        private const val FETCH_TIMEOUT_MS = 25_000L
        private const val BONDED_CANDIDATE_TIMEOUT_MS = 5_000L
        private const val MAX_BONDED_CANDIDATES = 6
        private const val TAG = "BleApiKeyFetcher"
    }
}
