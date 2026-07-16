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
import com.wearhealth.companion.shared.BleSyncProtocol
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 通过 BLE 从手机同步器拉取 Tavily API Key。
 *
 * 镜像 [BleDsSettingsFetcher] 的连接逻辑，仅区别在读取的特征值 UUID 为
 * [BleSyncProtocol.TAVILY_SETTINGS_UUID]，返回 JSON 含 [com.wearhealth.companion.shared.DataLayerPaths.KEY_TAVILY_API_KEY]。
 */
class BleTavilySettingsFetcher(private val context: Context) {
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

    /** 拉取 Tavily 设置 JSON。成功返回 JSON 字符串。 */
    fun fetch(done: (Result<String>) -> Unit) {
        if (callback != null) {
            done(Result.failure(IllegalStateException("Tavily 设置获取已在进行中")))
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
            if (gatt !== this@BleTavilySettingsFetcher.gatt) {
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
            this@BleTavilySettingsFetcher.gatt = gatt
            if (!gatt.discoverServices()) {
                if (tryingBondedCandidate) connectNextBondedCandidate()
                else finish(Result.failure(IllegalStateException("无法发现手机 GATT 服务")))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (finished || gatt !== this@BleTavilySettingsFetcher.gatt) return
            val service = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(BleSyncProtocol.SERVICE_UUID)
            } else null
            val characteristic = service?.getCharacteristic(BleSyncProtocol.TAVILY_SETTINGS_UUID)
            if (characteristic == null) {
                if (tryingBondedCandidate) connectNextBondedCandidate()
                else finish(Result.failure(IllegalStateException("手机同步器没有 Tavily 设置特征值")))
                return
            }
            matchedService = true
            handler.removeCallbacks(candidateTimeout)
            if (!gatt.readCharacteristic(characteristic)) {
                finish(Result.failure(IllegalStateException("无法请求读取 Tavily 设置")))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (finished || gatt !== this@BleTavilySettingsFetcher.gatt ||
                characteristic.uuid != BleSyncProtocol.TAVILY_SETTINGS_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(Result.failure(IllegalStateException("手机拒绝读取 Tavily 设置（GATT $status）")))
                return
            }
            val bytes = characteristic.value ?: ByteArray(0)
            val json = String(bytes, Charsets.UTF_8).trim()
            if (json.isEmpty() || json == "{}") {
                finish(Result.failure(IllegalStateException("手机端尚未配置 Tavily API Key")))
                return
            }
            try {
                JSONObject(json)
            } catch (e: Exception) {
                finish(Result.failure(IllegalStateException("Tavily 设置 JSON 解析失败: ${e.message}")))
                return
            }
            phoneStore.save(gatt.device)
            finish(Result.success(json))
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
                "已直连检查 $probedBondedCandidates 个系统配对设备；手表扫描正常，但未看到手机同步器"
            probedBondedCandidates > 0 ->
                "已直连检查 $probedBondedCandidates 个系统配对设备但未找到本项目服务，且手表未收到 BLE 广播；请确认手机后台同步已开启并重启 BLE 同步器"
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
    }
}
