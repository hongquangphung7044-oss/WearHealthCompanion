package com.wearhealth.companion.mobile.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.mobile.data.AppDatabase
import com.wearhealth.companion.mobile.data.EcgMeasurementEntity
import com.wearhealth.companion.mobile.data.MeasurementRepository
import com.wearhealth.companion.mobile.pdf.PdfExporter
import com.wearhealth.companion.mobile.service.DataLayerManager
import com.wearhealth.companion.mobile.service.PhoneWearableListenerService
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val repository = MeasurementRepository(AppDatabase.get(app).ecgMeasurementDao())
    private val dataLayer = DataLayerManager(app)

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

    /** PDF 导出结果（输出文件路径，失败时为 null） */
    private val _pdfExportResult = MutableStateFlow<String?>(null)
    val pdfExportResult: StateFlow<String?> = _pdfExportResult.asStateFlow()

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

    /** 刷新已连接手表名称 */
    fun refreshWatchConnection() {
        viewModelScope.launch {
            try {
                val nodes = dataLayer.getConnectedNodes()
                _connectedWatchName.value = nodes.firstOrNull()?.displayName
            } catch (e: Exception) {
                Log.w(TAG, "刷新手表连接失败: ${e.message}")
            }
        }
    }

    /** 发送 API Key 到手表 */
    fun sendApiKey(key: String) {
        viewModelScope.launch {
            val ok = dataLayer.sendApiKeyToWatch(key)
            _apiKeySendResult.value = if (ok) "API Key 已发送到手表" else "发送失败，请确认手表已连接"
        }
    }

    /** 请求手表同步未传送数据 */
    fun requestSync() {
        viewModelScope.launch {
            val sent = dataLayer.requestSyncFromWatch()
            _syncResult.value = when {
                sent > 0 -> "已向 $sent 个手表发送同步请求"
                else -> "没有已连接的手表"
            }
        }
    }

    /**
     * 按 id 加载单条测量记录（含完整波形），用于详情页
     */
    suspend fun loadMeasurement(id: Long): EcgMeasurementTransfer? = repository.getById(id)

    /** 删除一条记录 */
    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /**
     * 导出 PDF 报告
     * @param transfer 已加载的完整测量数据
     */
    fun exportPdf(transfer: EcgMeasurementTransfer) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _exporting.value = true
            _pdfExportResult.value = null
            try {
                val path = PdfExporter.export(context, transfer)
                _pdfExportResult.value = path
            } catch (e: Exception) {
                Log.e(TAG, "导出 PDF 失败: ${e.message}", e)
                _pdfExportResult.value = null
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
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(updateReceiver)
    }

    companion object {
        private const val TAG = "MobileViewModel"
    }
}
