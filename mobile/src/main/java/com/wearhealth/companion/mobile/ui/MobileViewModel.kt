package com.wearhealth.companion.mobile.ui

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.mobile.data.AppDatabase
import com.wearhealth.companion.mobile.data.EcgMeasurementEntity
import com.wearhealth.companion.mobile.data.MeasurementRepository
import com.wearhealth.companion.mobile.data.MobileApiKeyStore
import com.wearhealth.companion.mobile.pdf.PdfExportResult
import com.wearhealth.companion.mobile.pdf.PdfExporter
import com.wearhealth.companion.mobile.service.BleSyncForegroundService
import com.wearhealth.companion.mobile.service.BleSyncPreferences
import com.wearhealth.companion.mobile.service.BleSyncRuntime
import com.wearhealth.companion.mobile.service.BleSyncStatusStore
import com.wearhealth.companion.mobile.service.DataLayerManager
import com.wearhealth.companion.mobile.service.PhoneWearableListenerService
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机端主 ViewModel
 *
 * 持有 Room 仓库与 Wearable Data Layer 管理器，负责：
 * - 暴露测量记录列表（响应式，Room 自动推送变更）
 * - 发送 API Key 到手表
 * - 请求手表同步未传送数据
 * - 导出 PDF 报告
 * - 暴露已连接手表名称
 */
class MobileViewModel(app: Application) : AndroidViewModel(app) {

    @Volatile
    private var appVisible = true

    private val repository = MeasurementRepository(AppDatabase.get(app).ecgMeasurementDao())
    private val dataLayer = DataLayerManager(app)
    private val mobileApiKeyStore = MobileApiKeyStore(app)

    /** Direct BLE receiver state. This remains useful when Google Data Layer is absent. */
    val bleSyncStatus: StateFlow<String> = BleSyncRuntime.status(app)
    val bleConnected: StateFlow<Boolean> = BleSyncRuntime.connected(app)

    private val _backgroundSyncEnabled = MutableStateFlow(BleSyncPreferences.isEnabled(app))
    val backgroundSyncEnabled: StateFlow<Boolean> = _backgroundSyncEnabled.asStateFlow()

    val backgroundSyncMessage: StateFlow<String?> = BleSyncStatusStore.message

    /** 全部测量记录（按时间倒序，Room 自动响应插入/删除） */
    val measurements: StateFlow<List<EcgMeasurementEntity>> =
        repository.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已连接的手表名称（无手表时为 null） */
    private val _connectedWatchName = MutableStateFlow<String?>(null)
    val connectedWatchName: StateFlow<String?> = _connectedWatchName.asStateFlow()

    /** API Key 发送状态 */
    private val _apiKeySendResult = MutableStateFlow<String?>(null)
    val apiKeySendResult: StateFlow<String?> = _apiKeySendResult.asStateFlow()

    /** 同步请求结果 */
    private val _syncResult = MutableStateFlow<String?>(null)
    val syncResult: StateFlow<String?> = _syncResult.asStateFlow()

    /** PDF export result uses a content URI and a user-visible shared-storage label. */
    private val _pdfExportResult = MutableStateFlow<PdfExportResult?>(null)
    val pdfExportResult: StateFlow<PdfExportResult?> = _pdfExportResult.asStateFlow()

    private val _pdfExportError = MutableStateFlow<String?>(null)
    val pdfExportError: StateFlow<String?> = _pdfExportError.asStateFlow()

    /** 导出进行中标志 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    // 监听手表数据到达广播（Room Flow 本身会推送，这里额外刷新手表连接状态）
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneWearableListenerService.ACTION_ECG_UPDATED) {
                refreshWatchConnection()
            }
        }
    }

    init {
        // 注册包内广播
        val filter = IntentFilter(PhoneWearableListenerService.ACTION_ECG_UPDATED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(updateReceiver, filter)
        }
        refreshWatchConnection()
    }

    /** Start the foreground service when enabled; otherwise listen only while the app is visible. */
    fun startBleSync() {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).any {
                ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            BleSyncStatusStore.setMessage("请先授予附近设备权限以启动 BLE 同步")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            BleSyncPreferences.isEnabled(app) &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            BleSyncStatusStore.setMessage("请授予通知权限以启动后台 BLE 同步")
            return
        }
        if (BleSyncPreferences.isEnabled(app)) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, BleSyncForegroundService::class.java))
            } catch (error: Exception) {
                BleSyncStatusStore.setMessage("后台同步启动失败：${error.message ?: "系统限制"}")
            }
        } else {
            BleSyncRuntime.start(app)
        }
    }

    fun restartBleSync() = BleSyncRuntime.restart(getApplication())

    fun setBackgroundSyncEnabled(enabled: Boolean, notificationPermissionGranted: Boolean = true) {
        val app = getApplication<Application>()
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
            BleSyncPreferences.setEnabled(app, false)
            _backgroundSyncEnabled.value = false
            BleSyncStatusStore.setMessage("需要通知权限才能显示后台同步常驻通知；当前仅在 App 前台监听")
            BleSyncRuntime.start(app)
            return
        }
        BleSyncPreferences.setEnabled(app, enabled)
        _backgroundSyncEnabled.value = enabled
        if (enabled) {
            BleSyncStatusStore.setMessage("后台 BLE 同步已开启；将显示常驻通知")
            startBleSync()
        } else {
            app.stopService(Intent(app, BleSyncForegroundService::class.java))
            BleSyncRuntime.stop(app)
            BleSyncStatusStore.setMessage("后台 BLE 同步已关闭；打开 App 时仍可手工同步")
            viewModelScope.launch {
                delay(350L)
                if (appVisible && !BleSyncPreferences.isEnabled(app)) BleSyncRuntime.start(app)
            }
        }
    }

    fun onBluetoothPermissionDenied() {
        val app = getApplication<Application>()
        BleSyncPreferences.setEnabled(app, false)
        _backgroundSyncEnabled.value = false
        BleSyncRuntime.stop(app)
        BleSyncStatusStore.setMessage("附近设备权限未授予，后台 BLE 同步已关闭")
    }

    fun onAppForegrounded() {
        appVisible = true
        startBleSync()
    }

    fun onAppBackgrounded() {
        appVisible = false
        val app = getApplication<Application>()
        if (!BleSyncPreferences.isEnabled(app)) BleSyncRuntime.stop(app)
    }

    /**
     * Refresh optional Google Data Layer discovery and make sure the BLE fallback is advertising.
     * A null [connectedWatchName] means only that GMS Data Layer did not find a node; it does not
     * mean the Bluetooth-paired watch cannot use the BLE synchronizer.
     */
    fun refreshWatchConnection() {
        startBleSync()
        viewModelScope.launch {
            try {
                val nodes = dataLayer.getConnectedNodes()
                _connectedWatchName.value = nodes.firstOrNull()?.displayName
            } catch (e: Exception) {
                Log.w(TAG, "刷新手表连接失败: ${e.message}")
            }
        }
    }

    /** Save the key locally for BLE pull, then also submit it over Data Layer when available. */
    fun sendApiKey(key: String) {
        // 输入保存前归一化校验；失败显示中文错误且不保存，避免覆盖旧的有效 Key。
        val result = ApiKeyValidator.normalizeApiKey(key)
        val normalized = result.getOrNull()
        if (normalized == null) {
            _apiKeySendResult.value = result.exceptionOrNull()?.message ?: "API Key 无效"
            return
        }
        mobileApiKeyStore.save(normalized)
        _apiKeySendResult.value = "API Key 已保存到手机；请在手表点“从手机 BLE 获取”"
        viewModelScope.launch {
            if (dataLayer.sendApiKeyToWatch(normalized)) {
                _apiKeySendResult.value = "API Key 已保存，并已通过 Google 通道提交；国行手表也可主动通过 BLE 获取"
            }
        }
    }

    /** 请求手表同步未传送数据 */
    fun requestSync() {
        viewModelScope.launch {
            val sent = dataLayer.requestSyncFromWatch()
            _syncResult.value = when {
                sent > 0 -> "已通过 Google 通道向 $sent 个手表发送同步请求"
                else -> "Google 通道未发现手表；国行 BLE 请从手表详情点“传送到手机”"
            }
        }
    }

    /**
     * 按 id 加载单条测量记录（含完整波形），用于详情页
     */
    suspend fun loadMeasurement(id: Long): EcgMeasurementTransfer? = repository.getById(id)

    /** 删除一条手机 Room 记录，完成后在主线程回调 UI。 */
    fun delete(id: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    /**
     * 导出 PDF 报告
     * @param transfer 已加载的完整测量数据
     */
    fun exportPdf(transfer: EcgMeasurementTransfer, destination: Uri? = null) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _exporting.value = true
            _pdfExportResult.value = null
            _pdfExportError.value = null
            try {
                _pdfExportResult.value = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        PdfExporter.exportToDownloads(context, transfer)
                    } else {
                        requireNotNull(destination) { "请先选择 PDF 保存位置" }
                        PdfExporter.exportToUri(context, transfer, destination)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "导出 PDF 失败", e)
                _pdfExportError.value = "导出失败：${e.message ?: "无法写入所选位置"}"
            } finally {
                _exporting.value = false
            }
        }
    }

    /** 清除一次性结果消息 */
    fun clearMessages() {
        _apiKeySendResult.value = null
        _syncResult.value = null
        _pdfExportResult.value = null
        _pdfExportError.value = null
    }

    override fun onCleared() {
        if (!BleSyncPreferences.isEnabled(getApplication())) {
            BleSyncRuntime.stop(getApplication())
        }
        super.onCleared()
        getApplication<Application>().unregisterReceiver(updateReceiver)
    }

    companion object {
        private const val TAG = "MobileViewModel"
    }
}
