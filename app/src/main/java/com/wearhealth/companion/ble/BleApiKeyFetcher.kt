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
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleSyncProtocol.SERVICE_UUID)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
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
            if (finished) return
            if (result.device.bondState != BluetoothDevice.BOND_BONDED) {
                observedUnbondedState = result.device.bondState
                android.util.Log.w(TAG, "忽略未与手表配对的 BLE 设备（bondState=${result.device.bondState}）")
                return
            }
            manager?.adapter?.bluetoothLeScanner?.stopScan(this)
            gatt = result.device.connectGatt(context, false, gattCallback)
            if (gatt == null) finish(Result.failure(IllegalStateException("无法连接手机 BLE 同步器")))
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
            if (!gatt.requestMtu(BleSyncProtocol.PREFERRED_MTU)) gatt.discoverServices()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!finished) gatt.discoverServices()
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
        val message = if (state != null) {
            "已发现手机 BLE 广播，但系统报告未配对（bondState=$state）；请检查 Galaxy Wearable 配对状态"
        } else {
            "未发现手机 BLE 同步器；请保持手机 App 前台"
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
