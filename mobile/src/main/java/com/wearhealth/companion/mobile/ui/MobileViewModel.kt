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
import com.wearhealth.companion.mobile.data.MobileDeepSeekSettings
import com.wearhealth.companion.mobile.data.MobileTavilySettings
import com.wearhealth.companion.mobile.pdf.DiagnosticExporterAndroid
import com.wearhealth.companion.mobile.pdf.PdfExportResult
import com.wearhealth.companion.mobile.pdf.PdfExporter
import com.wearhealth.companion.mobile.service.BleSyncForegroundService
import com.wearhealth.companion.mobile.service.BleSyncPreferences
import com.wearhealth.companion.mobile.service.BleSyncRuntime
import com.wearhealth.companion.mobile.service.BleSyncStatusStore
import com.wearhealth.companion.mobile.service.DataLayerManager
import com.wearhealth.companion.mobile.service.PhoneWearableListenerService
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.DeepSeekApiClient
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
    private val dsSettings = MobileDeepSeekSettings(app)
    private var deepSeekApi = DeepSeekApiClient(dsSettings.getApiKey())
    private val tavilySettings = MobileTavilySettings(app)

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

    /** DeepSeek 设置发送状态 */
    private val _dsSendResult = MutableStateFlow<String?>(null)
    val dsSendResult: StateFlow<String?> = _dsSendResult.asStateFlow()

    /** DeepSeek 余额 */
    private val _dsBalance = MutableStateFlow<DeepSeekApiClient.BalanceInfo?>(null)
    val dsBalance: StateFlow<DeepSeekApiClient.BalanceInfo?> = _dsBalance.asStateFlow()

    private val _dsBalanceLoading = MutableStateFlow(false)
    val dsBalanceLoading: StateFlow<Boolean> = _dsBalanceLoading.asStateFlow()

    private val _dsBalanceError = MutableStateFlow<String?>(null)
    val dsBalanceError: StateFlow<String?> = _dsBalanceError.asStateFlow()

    /** DeepSeek 设置是否已配置 */
    private val _dsConfigured = MutableStateFlow(dsSettings.isConfigured())
    val dsConfigured: StateFlow<Boolean> = _dsConfigured.asStateFlow()

    /** 当前 DS 默认模型 */
    private val _dsDefaultModel = MutableStateFlow(dsSettings.getDefaultModel())
    val dsDefaultModel: StateFlow<DeepSeekApiClient.Model> = _dsDefaultModel.asStateFlow()

    /** 当前 DS 默认思考强度 */
    private val _dsDefaultThinking = MutableStateFlow(dsSettings.getDefaultThinking())
    val dsDefaultThinking: StateFlow<DeepSeekApiClient.ThinkingMode> = _dsDefaultThinking.asStateFlow()

    /** Tavily 搜索 Key 是否已配置（Max 档联网检索医学文献，可选） */
    private val _tavilyConfigured = MutableStateFlow(tavilySettings.isConfigured())
    val tavilyConfigured: StateFlow<Boolean> = _tavilyConfigured.asStateFlow()

    /** Tavily 下发结果消息 */
    private val _tavilySendResult = MutableStateFlow<String?>(null)
    val tavilySendResult: StateFlow<String?> = _tavilySendResult.asStateFlow()

    /** PDF export result uses a content URI and a user-visible shared-storage label. */
    private val _pdfExportResult = MutableStateFlow<PdfExportResult?>(null)
    val pdfExportResult: StateFlow<PdfExportResult?> = _pdfExportResult.asStateFlow()

    private val _pdfExportError = MutableStateFlow<String?>(null)
    val pdfExportError: StateFlow<String?> = _pdfExportError.asStateFlow()

    /** 导出进行中标志 */
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** 诊断包导出结果（保存路径标签，供 UI 显示） */
    private val _diagnosticExportPath = MutableStateFlow<String?>(null)
    val diagnosticExportPath: StateFlow<String?> = _diagnosticExportPath.asStateFlow()

    private val _diagnosticExportError = MutableStateFlow<String?>(null)
    val diagnosticExportError: StateFlow<String?> = _diagnosticExportError.asStateFlow()

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

    /** 清除手机端缓存：API Key + 所有 ECG 历史记录，并重启 BLE 同步器。手表端不受影响。 */
    fun clearCache() {
        viewModelScope.launch {
            repository.deleteAll()
            mobileApiKeyStore.clear()
            dsSettings.clearApiKey()
            _dsConfigured.value = false
            BleSyncRuntime.restart(getApplication())
            BleSyncStatusStore.setMessage("已清除手机端缓存（API Key、ECG 历史）并重启 BLE 监听")
        }
    }

    // ========== DeepSeek 设置管理 ==========

    /**
     * 保存 DeepSeek API Key 并下发到手表
     *
     * 注：模型/思考强度/年龄/性别由手表端选择和输入，手机端只负责下发 API Key。
     * 下发时仍传完整字段以保持 BLE/Data Layer 协议兼容（手表端会忽略这些字段）。
     */
    fun saveAndSendDeepSeekSettings(apiKey: String) {
        if (apiKey.isNotBlank()) {
            dsSettings.saveApiKey(apiKey)
            deepSeekApi = DeepSeekApiClient(apiKey)
            _dsConfigured.value = true
        }

        _dsSendResult.value = "DeepSeek 设置已保存"
        viewModelScope.launch {
            // 协议兼容：仍下发完整字段，手表端忽略 model/thinking/age/gender
            val sent = dataLayer.sendDeepSeekSettingsToWatch(
                apiKey = dsSettings.getApiKey(),
                defaultModel = DeepSeekApiClient.Model.FLASH,
                defaultThinking = DeepSeekApiClient.ThinkingMode.BALANCED,
                userAge = 0,
                userIsMale = null,
            )
            _dsSendResult.value = if (sent) {
                "DeepSeek 设置已保存，并已下发到手表"
            } else {
                "DeepSeek 设置已保存（下发手表失败，可稍后重试）"
            }
        }
    }

    /** 查询 DeepSeek 账户余额 */
    fun queryDeepSeekBalance() {
        if (_dsBalanceLoading.value) return
        if (!dsSettings.isConfigured()) {
            _dsBalanceError.value = "未配置 DeepSeek API Key"
            return
        }
        _dsBalanceLoading.value = true
        _dsBalanceError.value = null
        viewModelScope.launch {
            val result = deepSeekApi.queryBalance()
            result.onSuccess { balance ->
                _dsBalance.value = balance
                _dsBalanceLoading.value = false
                _dsBalanceError.value = null
            }.onFailure { error ->
                _dsBalanceLoading.value = false
                _dsBalanceError.value = error.message ?: "余额查询失败"
            }
        }
    }

    /** 获取当前 DS 配置（供 UI 初始化读取） */
    fun getDsSettingsSnapshot(): DeepSeekSettingsSnapshot = DeepSeekSettingsSnapshot(
        apiKey = dsSettings.getApiKey(),
        model = dsSettings.getDefaultModel(),
        thinking = dsSettings.getDefaultThinking(),
        userAge = dsSettings.getUserAge(),
        userIsMale = dsSettings.getUserIsMale(),
    )

    data class DeepSeekSettingsSnapshot(
        val apiKey: String,
        val model: DeepSeekApiClient.Model,
        val thinking: DeepSeekApiClient.ThinkingMode,
        val userAge: Int,
        val userIsMale: Boolean?,
    )

    // ========== Tavily 设置管理 ==========

    /**
     * 保存 Tavily API Key 并下发到手表（供 DS Max 档联网检索医学文献）
     *
     * 镜像 [saveAndSendDeepSeekSettings] 的安全模式：保存走 [MobileTavilySettings.saveApiKey]
     * （含 NUL/控制字符校验），下发用 [DataLayerManager.sendTavilySettingsToWatch]。
     */
    fun saveAndSendTavilySettings(apiKey: String) {
        if (apiKey.isNotBlank()) {
            tavilySettings.saveApiKey(apiKey)
            _tavilyConfigured.value = true
        }
        _tavilySendResult.value = "Tavily 设置已保存"
        viewModelScope.launch {
            val sent = dataLayer.sendTavilySettingsToWatch(tavilySettings.getApiKey())
            _tavilySendResult.value = if (sent) {
                "Tavily 设置已保存，并已下发到手表"
            } else {
                "Tavily 设置已保存（下发手表失败，可稍后重试）"
            }
        }
    }

    /** 获取当前 Tavily API Key（供 UI 初始化读取） */
    fun getTavilyApiKey(): String = tavilySettings.getApiKey()

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

    /**
     * 导出算法诊断包（纯文本 .txt）：原始 ECG 数据 + 算法特征 + AI 解读三段对照。
     *
     * 用途：用户复制贴给助手，助手据此精确定位原始数据/本地算法/AI 哪一步出错。
     * 文件写入 Download/WearHealthCompanion/ECG_diagnostic_*.txt（API 29+），
     * 或 SAF 选择位置（API 26-28）。
     *
     * @param transfer 已加载的完整测量数据
     * @param destination SAF 选择的 URI（仅 API 26-28 需要）
     */
    fun exportDiagnostic(transfer: EcgMeasurementTransfer, destination: Uri? = null) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _exporting.value = true
            _diagnosticExportPath.value = null
            _diagnosticExportError.value = null
            try {
                _diagnosticExportPath.value = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        DiagnosticExporterAndroid.exportToDownloads(context, transfer)
                    } else {
                        requireNotNull(destination) { "请先选择诊断包保存位置" }
                        DiagnosticExporterAndroid.exportToUri(context, transfer, destination)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "导出诊断包失败", e)
                _diagnosticExportError.value = "导出失败：${e.message ?: "无法写入所选位置"}"
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
        _diagnosticExportPath.value = null
        _diagnosticExportError.value = null
        _dsSendResult.value = null
        _dsBalanceError.value = null
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
