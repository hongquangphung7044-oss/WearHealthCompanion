package com.wearhealth.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.HistoryItem
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.computeMinMaxHeartRate
import com.wearhealth.companion.model.localSignalQualityCheck
import com.wearhealth.companion.network.HeartVoiceApiClient
import com.wearhealth.companion.sensor.EcgCollector
import com.wearhealth.companion.data.EcgRawArchive
import com.wearhealth.companion.sync.WearEcgSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EcgUiState(
    val ecgState: EcgCollectionState = EcgCollectionState.Idle,
    val ecgResult: EcgAnalysisResult? = null,
    val sdkAvailable: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val liveSamples: List<Int> = emptyList(),    // 实时/最终 ECG 波形（用于 UI 绘制）
    val showHistory: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val historyDetail: HistoryItem? = null,      // 点击进入的历史详情
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val ecgCollector = EcgCollector(app.applicationContext)
    private val heartVoiceApi = HeartVoiceApiClient(app.applicationContext)
    private val rawArchive = EcgRawArchive(app.applicationContext)
    private val historyRepo = EcgHistoryRepository(app.applicationContext)

    private val _uiState = MutableStateFlow(EcgUiState())
    val uiState: StateFlow<EcgUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            sdkAvailable = ecgCollector.checkSdkAvailable(),
            apiKeyConfigured = heartVoiceApi.isApiKeyConfigured,
        )
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
                // 保存到历史记录
                val saved = historyRepo.save(finalResult, rawSampleCount = ecgData.size)
                rawArchive.save(saved.recordId, ecgData)
                // The status remains SENDING until the phone has persisted the record and sends an ACK.
                if (WearEcgSync.send(getApplication<Application>().applicationContext, saved)) {
                    historyRepo.markSending(saved.recordId)
                }
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
        _uiState.value = _uiState.value.copy(historyDetail = item)
    }

    fun hideHistoryDetail() {
        _uiState.value = _uiState.value.copy(historyDetail = null)
    }

    fun deleteHistory(timestamp: Long) {
        historyRepo.getAll().firstOrNull { it.timestamp == timestamp }?.let { rawArchive.delete(it.recordId) }
        historyRepo.delete(timestamp)
        _uiState.value = _uiState.value.copy(
            history = historyRepo.getAll(),
            historyDetail = null,
        )
    }

    fun deleteAllHistory() {
        rawArchive.deleteAll()
        historyRepo.deleteAll()
        _uiState.value = _uiState.value.copy(history = emptyList(), historyDetail = null)
    }


    fun sendHistoryToPhone(item: HistoryItem) {
        viewModelScope.launch {
            if (WearEcgSync.send(getApplication<Application>().applicationContext, item)) {
                historyRepo.markSending(item.recordId)
            } else {
                historyRepo.markPending(item.recordId)
            }
            val records = historyRepo.getAll()
            _uiState.value = _uiState.value.copy(
                history = records,
                historyDetail = records.firstOrNull { it.recordId == item.recordId },
            )
        }
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
            apiKeyConfigured = heartVoiceApi.isApiKeyConfigured,
            liveSamples = emptyList(),
        )
    }

    override fun onCleared() {
        ecgCollector.disconnect()
        super.onCleared()
    }
}
