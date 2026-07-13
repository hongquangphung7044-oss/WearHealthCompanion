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
     * 启动 ECG 测量：采集 30 秒 ECG 波形 → 发送到 HeartVoice API 分析
     */
    fun startEcgMeasurement() {
        if (_uiState.value.ecgState is EcgCollectionState.Collecting ||
            _uiState.value.ecgState is EcgCollectionState.Connecting ||
            _uiState.value.ecgState is EcgCollectionState.Analyzing
        ) return

        viewModelScope.launch {
            // 1. 采集 ECG 原始波形
            val ecgData = ecgCollector.startEcgCollection(targetDurationSec = 30)

            if (ecgData.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error("ECG 采集失败，请确保手指按住上方按键")
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
            )

            // 2. 发送到 HeartVoice API 分析
            val result = heartVoiceApi.analyzeEcg(ecgData, sampleRate = 500)

            result.onSuccess { analysis ->
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Done(analysis),
                    ecgResult = analysis,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error(error.message ?: "API 分析失败")
                )
            }
        }
    }

    fun resetEcg() {
        ecgCollector.disconnect()
        _uiState.value = _uiState.value.copy(
            ecgState = EcgCollectionState.Idle,
            ecgResult = null,
        )
    }

    override fun onCleared() {
        ecgCollector.disconnect()
        super.onCleared()
    }
}
