package com.wearhealth.companion.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.wearhealth.companion.data.EcgRawArchive
import com.wearhealth.companion.data.HistoryItem
import org.json.JSONObject
import java.nio.ByteBuffer

/** Direct BLE GATT sender for GMS-free watches. A phone running the companion app advertises the service. */
class BleEcgUploader(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var pending: Pair<HistoryItem, (Boolean) -> Unit>? = null
    private var chunks = emptyList<ByteArray>(); private var index = 0

    fun upload(item: HistoryItem, done: (Boolean) -> Unit) {
        val raw = EcgRawArchive(context).readCompressed(item.recordId) ?: return done(false)
        if (!allowed() || manager?.adapter?.isEnabled != true) return done(false)
        pending = item to done
        val meta = JSONObject().apply { put("timestamp",item.timestamp); put("diagnosis",item.diagnosis.joinToString(",")); put("hr",item.avgHeartRate); put("minHr",item.minHeartRate); put("maxHr",item.maxHeartRate); put("quality",item.signalQuality); put("abnormal",item.isAbnormal); put("qrs",item.avgQrs); put("pr",item.prInterval); put("qt",item.avgQt); put("qtc",item.avgQtc); put("pac",item.pacCount); put("pvc",item.pvcCount); put("sampleCount",item.rawSampleCount) }.toString().toByteArray()
        val begin = ByteBuffer.allocate(1+16+4+meta.size).put(BleProtocol.TYPE_BEGIN).put(BleProtocol.uuidBytes(item.recordId)).putInt(raw.size).put(meta).array()
        chunks = buildList { add(begin); raw.asList().chunked(BleProtocol.CHUNK_SIZE).forEach { add(byteArrayOf(BleProtocol.TYPE_CHUNK) + it.toByteArray()) }; add(byteArrayOf(BleProtocol.TYPE_END)) }
        scanner = manager.adapter.bluetoothLeScanner
        scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(BleProtocol.SERVICE_UUID)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.postDelayed({ finish(false) }, 20_000)
    }
    @SuppressLint("MissingPermission") private val scan = object: ScanCallback() { override fun onScanResult(type:Int,result:android.bluetooth.le.ScanResult) { scanner?.stopScan(this); gatt=result.device.connectGatt(context,false,callback) } }
    @SuppressLint("MissingPermission") private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,state:Int){ if(status!=BluetoothGatt.GATT_SUCCESS||state!=BluetoothProfile.STATE_CONNECTED) finish(false) else { g.requestMtu(247); g.discoverServices() } }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int){ val c=g.getService(BleProtocol.SERVICE_UUID)?.getCharacteristic(BleProtocol.UPLOAD_UUID) ?: return finish(false); gatt=g; write(c) }
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){ if(status!=BluetoothGatt.GATT_SUCCESS) finish(false) else { index++; write(c) } }
    }
    @SuppressLint("MissingPermission") private fun write(c:BluetoothGattCharacteristic){ if(index>=chunks.size) return finish(true); c.value=chunks[index]; if(gatt?.writeCharacteristic(c)!=true) finish(false) }
    @SuppressLint("MissingPermission") private fun finish(ok:Boolean){ scanner?.stopScan(scan); scanner=null; gatt?.close(); gatt=null; pending?.let{ it.second(ok) }; pending=null; chunks=emptyList(); index=0 }
    private fun allowed()= if(Build.VERSION.SDK_INT>=31) listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT).all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}else true
}
