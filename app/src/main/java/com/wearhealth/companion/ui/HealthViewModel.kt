package com.wearhealth.companion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
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
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val ecgCollector = EcgCollector(app.applicationContext)
    private val heartVoiceApi = HeartVoiceApiClient()

    private val _uiState = MutableStateFlow(EcgUiState())
    val uiState: StateFlow<EcgUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            sdkAvailable = ecgCollector.checkSdkAvailable(),
            apiKeyConfigured = heartVoiceApi.isApiKeyConfigured,
        )
    }

    /**
     * 启动 ECG 测量：预热 → 30 秒采集 → HeartVoice API 分析
     */
    fun startEcgMeasurement() {
        val current = _uiState.value.ecgState
        if (current is EcgCollectionState.Connecting ||
            current is EcgCollectionState.Collecting ||
            current is EcgCollectionState.Analyzing
        ) return

        viewModelScope.launch {
            // 同步 EcgCollector 的实时状态到 UI
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

            // 采集 ECG
            val ecgData = ecgCollector.startEcgCollection(targetDurationSec = 30)

            stateJob.cancel()
            sampleJob.cancel()

            if (ecgData.isEmpty()) {
                // 错误状态已由 EcgCollector 设置
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
            )

            // 发送到 HeartVoice API 分析
            val result = heartVoiceApi.analyzeEcg(ecgData, sampleRate = 500)

            result.onSuccess { analysis ->
                // 降采样波形存入结果
                val waveform = downsample(ecgData, 600)
                val finalResult = analysis.copy(ecgSamples = waveform)
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
