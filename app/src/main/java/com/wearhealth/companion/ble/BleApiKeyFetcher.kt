package com.wearhealth.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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

/** Fetches the HeartVoice API key from the paired phone without requiring an ECG history item. */
class BleApiKeyFetcher(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var callback: ((Result<String>) -> Unit)? = null
    private var finished = false
    private var observedUnbondedState: Int? = null
    private var observedResults = 0
    private var observedTarget = false
    private var targetConnectionStarted = false

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
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            done(Result.failure(IllegalStateException("手表蓝牙或 BLE 扫描不可用")))
            return
        }
        callback = done
        finished = false
        observedUnbondedState = null
        observedResults = 0
        observedTarget = false
        targetConnectionStarted = false
        try {
            // Do not put the custom 128-bit UUID in Android's hardware ScanFilter. Several
            // Samsung controllers silently drop matching advertisements. An empty filter keeps
            // this a filtered API call while BleScanSupport performs strict software matching.
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
            handler.postDelayed(timeout, TIMEOUT_MS)
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
            manager?.adapter?.bluetoothLeScanner?.stopScan(this)
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
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                finish(Result.failure(IllegalStateException("手机 BLE 连接失败（$status）")))
                return
            }
            this@BleApiKeyFetcher.gatt = gatt
            if (!gatt.discoverServices()) {
                finish(Result.failure(IllegalStateException("无法发现手机 GATT 服务")))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (finished) return
            val characteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.getService(BleSyncProtocol.SERVICE_UUID)
                    ?.getCharacteristic(BleSyncProtocol.API_KEY_UUID)
            } else null
            if (characteristic == null || !gatt.readCharacteristic(characteristic)) {
                finish(Result.failure(IllegalStateException("手机同步器没有可读取的 API Key")))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (finished || characteristic.uuid != BleSyncProtocol.API_KEY_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(Result.failure(IllegalStateException("手机拒绝读取 Key（GATT $status）；请确认系统配对与链路加密")))
                return
            }
            val key = characteristic.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
            if (key.isBlank()) {
                finish(Result.failure(IllegalStateException("手机端尚未保存 API Key")))
            } else if (key.toByteArray(Charsets.UTF_8).size > MAX_API_KEY_BYTES) {
                finish(Result.failure(IllegalStateException("手机端 API Key 长度无效")))
            } else {
                finish(Result.success(key))
            }
        }
    }

    private val timeout = Runnable {
        val state = observedUnbondedState
        val message = when {
            state != null ->
                "已发现手机同步器，但系统报告未配对（bondState=$state）；请检查 Galaxy Wearable 配对状态"
            observedTarget ->
                "已发现手机同步器广播，但未能建立安全连接"
            observedResults > 0 ->
                "手表扫描正常（看到 $observedResults 个周边广播），但未看到手机同步器；请在手机点重启 BLE 同步器"
            else ->
                "手表未收到任何 BLE 广播；请确认附近设备权限、关闭省电模式并保持屏幕亮起"
        }
        finish(Result.failure(IllegalStateException(message)))
    }

    @SuppressLint("MissingPermission")
    private fun finish(result: Result<String>) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
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
        private const val TIMEOUT_MS = 25_000L
        private const val MAX_API_KEY_BYTES = 512
        private const val TAG = "BleApiKeyFetcher"
    }
}
