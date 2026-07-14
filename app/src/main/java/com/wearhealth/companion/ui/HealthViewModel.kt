package com.wearhealth.companion.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearhealth.companion.data.ApiKeyManager
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.HistoryItem
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.computeMinMaxHeartRate
import com.wearhealth.companion.model.localSignalQualityCheck
import com.wearhealth.companion.network.HeartVoiceApiClient
import com.wearhealth.companion.sensor.EcgCollector
import com.wearhealth.companion.service.WatchWearableListenerService
import com.wearhealth.companion.shared.DataLayerPaths
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.shared.MeasurementSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EcgUiState(
    val ecgState: EcgCollectionState = EcgCollectionState.Idle,
    val ecgResult: EcgAnalysisResult? = null,
    val sdkAvailable: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val liveSamples: List<Int> = emptyList(),    // 实时/最终 ECG 波形（用于 UI 绘制）
    val showHistory: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val historyDetail: HistoryItem? = null,      // 点击进入的历史详情
    val syncingToPhone: Boolean = false,         // 是否正在传送到手机
    val syncMessage: String? = null,             // 传送状态消息
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val ecgCollector = EcgCollector(app.applicationContext)
    private val apiKeyManager = ApiKeyManager(app.applicationContext)
    private var heartVoiceApi = HeartVoiceApiClient(apiKeyManager.getApiKey())
    private val historyRepo = EcgHistoryRepository(app.applicationContext)

    private val _uiState = MutableStateFlow(EcgUiState())
    val uiState: StateFlow<EcgUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            sdkAvailable = ecgCollector.checkSdkAvailable(),
            apiKeyConfigured = apiKeyManager.isConfigured(),
        )
        // 监听 API Key 变更（手机端下发或本地保存后自动刷新）
        viewModelScope.launch {
            ApiKeyManager.refreshTrigger.collect {
                refreshApiKeyStatus()
            }
        }
        // SharedFlow has no initial event. The UI is updated only when the service has a real
        // submission result or when the phone sends a persistence ACK.
        viewModelScope.launch {
            WatchWearableListenerService.syncEvents.collect { message ->
                val records = historyRepo.getAll()
                _uiState.value = _uiState.value.copy(
                    history = records,
                    historyDetail = _uiState.value.historyDetail?.let { detail ->
                        records.find { it.timestamp == detail.timestamp } ?: detail
                    },
                    syncMessage = message,
                )
            }
        }
    }

    fun startEcgMeasurement() {
        val current = _uiState.value.ecgState
        if (current is EcgCollectionState.Connecting ||
            current is EcgCollectionState.Collecting ||
            current is EcgCollectionState.Analyzing
        ) return

        viewModelScope.launch {
            // 同步实时状态到 UI
            val stateJob = launch {
                ecgCollector.state.collect { state ->
                    _uiState.value = _uiState.value.copy(ecgState = state)
                }
            }
            val sampleJob = launch {
                ecgCollector.liveSamples.collect { samples ->
                    _uiState.value = _uiState.value.copy(liveSamples = samples)
                }
            }

            val ecgData = ecgCollector.startEcgCollection(targetDurationSec = 30)

            stateJob.cancel()
            sampleJob.cancel()

            if (ecgData.isEmpty()) return@launch

            // 本地信号质量预检（不发 API，节省用量）
            val qualityError = localSignalQualityCheck(ecgData, sampleRateHz = 500)
            if (qualityError != null) {
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error(qualityError)
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
            )

            val result = heartVoiceApi.analyzeEcg(ecgData, sampleRate = 500)

            result.onSuccess { analysis ->
                val waveform = downsample(ecgData, 400)
                // 本地计算 min/max 心率（API 只返回平均心率）
                val (minHr, maxHr) = computeMinMaxHeartRate(ecgData, sampleRateHz = 500)

                // WPW 误判过滤：如果 API 返回 WPW 但 PR/QRS 都正常，移除 WPW 诊断
                val filteredDiagnosis = filterWpwIfConflict(analysis.diagnosis,
                    analysis.prInterval, analysis.avgQrs)

                val finalResult = analysis.copy(
                    ecgSamples = waveform,
                    minHeartRate = minHr,
                    maxHeartRate = maxHr,
                    diagnosis = filteredDiagnosis,
                    isAbnormal = filteredDiagnosis.any {
                        it != "SN" && it != "SNT" && it != "SNB"
                    },
                )
                // 保存到历史记录（同时保存完整原始波形 rawEcgData，不降采样）
                historyRepo.save(finalResult, rawEcgData = ecgData)
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Done(finalResult),
                    ecgResult = finalResult,
                    liveSamples = waveform,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error(error.message ?: "API 分析失败")
                )
            }
        }
    }

    // ========== API Key 管理 ==========

    /**
     * 保存 API Key（用户在手表端输入或由手机下发后调用）
     *
     * 保存后会重建 HeartVoiceApiClient 并刷新 UI 状态。
     */
    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(syncMessage = "API Key 不能为空")
            return
        }
        apiKeyManager.saveApiKey(trimmed)
        // 用新的 API Key 重建客户端
        heartVoiceApi = HeartVoiceApiClient(trimmed)
        _uiState.value = _uiState.value.copy(
            apiKeyConfigured = true,
            syncMessage = "API Key 已保存",
        )
    }

    /**
     * 刷新 API Key 配置状态
     *
     * 在 onResume 或收到手机下发的新 Key 时调用。
     */
    fun refreshApiKeyStatus() {
        val apiKey = apiKeyManager.getApiKey()
        // 用最新的 API Key 重建客户端
        heartVoiceApi = HeartVoiceApiClient(apiKey)
        _uiState.value = _uiState.value.copy(
            apiKeyConfigured = apiKeyManager.isConfigured(),
        )
    }

    // ========== 传送到手机（Wearable Data Layer）==========

    /**
     * 将指定记录通过 Wearable Data Layer 发送到手机
     *
     * 使用 PutDataMapRequest + MeasurementSerializer.toDataMap() 传输完整数据，
     * 包括完整原始波形（二进制编码，约 60KB）。
     */
    fun syncToPhone(item: HistoryItem) {
        if (_uiState.value.syncingToPhone) return

        _uiState.value = _uiState.value.copy(
            syncingToPhone = true,
            syncMessage = "正在检测手机连接...",
        )

        viewModelScope.launch {
            try {
                // Only accept a reachable node that explicitly advertises this phone sync protocol.
                // This mirrors Authenticator/Stratum and avoids treating unrelated Wear nodes as a companion.
                val phoneNodes = withContext(Dispatchers.IO) {
                    Tasks.await(
                        Wearable.getCapabilityClient(getApplication()).getCapability(
                            DataLayerPaths.CAPABILITY_PHONE_SYNC,
                            CapabilityClient.FILTER_REACHABLE,
                        )
                    ).nodes
                }
                if (phoneNodes.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        syncingToPhone = false,
                        syncMessage = "未检测到可用手机同步器，请确认新版手机 App 已安装、已打开且手表与手机保持连接",
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    syncMessage = "正在传送到手机...",
                )

                val transfer = EcgMeasurementTransfer(
                    timestamp = item.timestamp,
                    diagnosis = item.diagnosis,
                    avgHeartRate = item.avgHeartRate,
                    minHeartRate = item.minHeartRate,
                    maxHeartRate = item.maxHeartRate,
                    signalQuality = item.signalQuality,
                    isAbnormal = item.isAbnormal,
                    avgQrs = item.avgQrs,
                    prInterval = item.prInterval,
                    avgQt = item.avgQt,
                    avgQtc = item.avgQtc,
                    pacCount = item.pacCount,
                    pvcCount = item.pvcCount,
                    rawEcgData = item.rawEcgData,
                    downsampledEcg = item.ecgSamples,
                    sampleRate = 500,
                )

                val putDataMapReq = PutDataMapRequest.create(
                    "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}"
                )
                putDataMapReq.dataMap.putAll(MeasurementSerializer.toDataMap(transfer))
                val putDataReq = putDataMapReq.asPutDataRequest()
                putDataReq.setUrgent()

                withContext(Dispatchers.IO) {
                    Tasks.await(Wearable.getDataClient(getApplication()).putDataItem(putDataReq))
                }

                // putDataItem only queues local Data Layer data. The phone must ACK after Room persistence.
                Log.i(TAG, "ECG 记录已提交，等待手机确认: ${item.timestamp}")
                _uiState.value = _uiState.value.copy(
                    syncingToPhone = false,
                    syncMessage = "已提交传送，等待手机确认保存…",
                )
            } catch (e: Exception) {
                Log.e(TAG, "传送到手机失败", e)
                _uiState.value = _uiState.value.copy(
                    syncingToPhone = false,
                    syncMessage = "传送失败: ${e.message ?: "未知错误"}",
                )
            }
        }
    }

    /**
     * 同步所有未传送到手机的记录
     *
     * 通常在收到手机的 /sync_request 消息时调用。
     */
    fun syncAllUnsynced() {
        val unsynced = historyRepo.getUnsynced()
        if (unsynced.isEmpty()) {
            _uiState.value = _uiState.value.copy(syncMessage = "没有未传送的记录")
            return
        }

        _uiState.value = _uiState.value.copy(
            syncingToPhone = true,
            syncMessage = "正在传送 ${unsynced.size} 条记录...",
        )

        viewModelScope.launch {
            var success = 0
            var failed = 0
            for (item in unsynced) {
                try {
                    val transfer = EcgMeasurementTransfer(
                        timestamp = item.timestamp,
                        diagnosis = item.diagnosis,
                        avgHeartRate = item.avgHeartRate,
                        minHeartRate = item.minHeartRate,
                        maxHeartRate = item.maxHeartRate,
                        signalQuality = item.signalQuality,
                        isAbnormal = item.isAbnormal,
                        avgQrs = item.avgQrs,
                        prInterval = item.prInterval,
                        avgQt = item.avgQt,
                        avgQtc = item.avgQtc,
                        pacCount = item.pacCount,
                        pvcCount = item.pvcCount,
                        rawEcgData = item.rawEcgData,
                        downsampledEcg = item.ecgSamples,
                        sampleRate = 500,
                    )
                    val putDataMapReq = PutDataMapRequest.create(
                        "${DataLayerPaths.PATH_ECG_MEASUREMENT}/${item.timestamp}"
                    )
                    putDataMapReq.dataMap.putAll(MeasurementSerializer.toDataMap(transfer))
                    val putDataReq = putDataMapReq.asPutDataRequest()
                    putDataReq.setUrgent()

                    withContext(Dispatchers.IO) {
                        Tasks.await(Wearable.getDataClient(getApplication()).putDataItem(putDataReq))
                    }
                    // Do not mark synced here; /ecg_ack/{timestamp} is the source of truth.
                    success++
                } catch (e: Exception) {
                    Log.e(TAG, "传送记录 ${item.timestamp} 失败", e)
                    failed++
                }
            }

            _uiState.value = _uiState.value.copy(
                syncingToPhone = false,
                syncMessage = "已提交 $success 条，等待手机确认" + if (failed > 0) "；提交失败 $failed 条" else "",
                history = historyRepo.getAll(),
                historyDetail = _uiState.value.historyDetail?.let { detail ->
                    historyRepo.getAll().find { it.timestamp == detail.timestamp } ?: detail
                },
            )
        }
    }

    /** 清除同步状态消息 */
    fun clearSyncMessage() {
        _uiState.value = _uiState.value.copy(syncMessage = null)
    }

    // ========== 历史记录管理 ==========

    fun showHistory() {
        _uiState.value = _uiState.value.copy(
            showHistory = true,
            history = historyRepo.getAll(),
            historyDetail = null,
        )
    }

    fun hideHistory() {
        _uiState.value = _uiState.value.copy(showHistory = false, historyDetail = null)
    }

    fun showHistoryDetail(item: HistoryItem) {
        // 从仓库重新读取，确保 syncedToPhone 状态最新
        val latest = historyRepo.getAll().find { it.timestamp == item.timestamp } ?: item
        _uiState.value = _uiState.value.copy(historyDetail = latest)
    }

    fun hideHistoryDetail() {
        _uiState.value = _uiState.value.copy(historyDetail = null)
    }

    fun deleteHistory(timestamp: Long) {
        historyRepo.delete(timestamp)
        _uiState.value = _uiState.value.copy(
            history = historyRepo.getAll(),
            historyDetail = null,
        )
    }

    fun deleteAllHistory() {
        historyRepo.deleteAll()
        _uiState.value = _uiState.value.copy(history = emptyList(), historyDetail = null)
    }

    private fun downsample(data: List<Int>, targetSize: Int): List<Int> {
        if (data.size <= targetSize) return data
        val step = data.size.toFloat() / targetSize
        val result = mutableListOf<Int>()
        var idx = 0f
        while (idx < data.size && result.size < targetSize) {
            result.add(data[idx.toInt()])
            idx += step
        }
        return result
    }

    /**
     * WPW 误判过滤
     *
     * WPW 典型三联征：PR<120ms + QRS>120ms + delta 波
     * 如果 API 返回 WPW 但 PR 和 QRS 都在正常范围，很可能是误判 → 移除 WPW 诊断
     * 保留其他诊断标签
     */
    private fun filterWpwIfConflict(diagnosis: List<String>, prInterval: Int, avgQrs: Int): List<String> {
        if (!diagnosis.contains("WPW")) return diagnosis
        // PR 正常 (120-200) 且 QRS 正常 (80-120) → WPW 矛盾，移除
        val prNormal = prInterval in 120..200
        val qrsNormal = avgQrs in 80..120
        return if (prNormal && qrsNormal && prInterval > 0 && avgQrs > 0) {
            // 移除 WPW，如果没有其他诊断则标记为窦性心律
            val filtered = diagnosis.filter { it != "WPW" }
            if (filtered.isEmpty()) listOf("SN") else filtered
        } else {
            diagnosis  // 参数确实异常，保留 WPW 诊断
        }
    }

    fun resetEcg() {
        ecgCollector.disconnect()
        _uiState.value = _uiState.value.copy(
            ecgState = EcgCollectionState.Idle,
            ecgResult = null,
            liveSamples = emptyList(),
        )
    }

    override fun onCleared() {
        ecgCollector.disconnect()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HealthViewModel"
    }
}
