package com.wearhealth.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.HistoryItem
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.computeMinMaxHeartRate
import com.wearhealth.companion.network.HeartVoiceApiClient
import com.wearhealth.companion.sensor.EcgCollector
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
    private val heartVoiceApi = HeartVoiceApiClient()
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

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
            )

            val result = heartVoiceApi.analyzeEcg(ecgData, sampleRate = 500)

            result.onSuccess { analysis ->
                val waveform = downsample(ecgData, 300)
                // 本地计算 min/max 心率（API 只返回平均心率）
                val (minHr, maxHr) = computeMinMaxHeartRate(ecgData, sampleRateHz = 500)
                val finalResult = analysis.copy(
                    ecgSamples = waveform,
                    minHeartRate = minHr,
                    maxHeartRate = maxHr,
                )
                // 保存到历史记录
                historyRepo.save(finalResult)
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
}
