package com.wearhealth.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.analysis.TFLiteAnalyzer
import com.wearhealth.companion.model.EcgAnalysisResult
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.HealthSample
import com.wearhealth.companion.model.HealthStatus
import com.wearhealth.companion.network.HeartVoiceApiClient
import com.wearhealth.companion.sensor.EcgCollector
import com.wearhealth.companion.sensor.HealthSensorCollector
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HealthUiState(
    val isMonitoring: Boolean = false,
    val latestSample: HealthSample = HealthSample.INVALID,
    val status: HealthStatus = HealthStatus.Unknown("未开始"),
    val history: List<HealthSample> = emptyList(),
    // ECG 相关
    val ecgState: EcgCollectionState = EcgCollectionState.Idle,
    val ecgResult: EcgAnalysisResult? = null,
    val ecgWaveform: List<Int> = emptyList(),
    val sdkAvailable: Boolean = false,
    val apiKeyConfigured: Boolean = false,
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val collector = HealthSensorCollector(app.applicationContext)
    private val analyzer = TFLiteAnalyzer(app.applicationContext)
    private val ecgCollector = EcgCollector(app.applicationContext)

    // HeartVoice API Key：从 BuildConfig 或环境变量读取
    // CI 构建时通过环境变量 HEARTVOICE_API_KEY 注入
    private val apiKey = System.getenv("HEARTVOICE_API_KEY") ?: ""
    private val heartVoiceApi = HeartVoiceApiClient(apiKey)

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    init {
        // 检查 SDK 和 API Key 是否可用
        _uiState.value = _uiState.value.copy(
            sdkAvailable = ecgCollector.checkSdkAvailable(),
            apiKeyConfigured = apiKey.isNotBlank(),
        )
    }

    fun startMonitoring() {
        if (_uiState.value.isMonitoring) return
        _uiState.value = _uiState.value.copy(isMonitoring = true)
        viewModelScope.launch {
            collector.collect().collectLatest { sample ->
                val status = analyzer.analyze(sample)
                _uiState.value = _uiState.value.copy(
                    latestSample = sample,
                    status = status,
                    history = (_uiState.value.history + sample).takeLast(MAX_HISTORY),
                )
            }
        }
    }

    fun stopMonitoring() {
        _uiState.value = _uiState.value.copy(isMonitoring = false)
    }

    /**
     * 启动 ECG 测量：采集 30 秒 ECG 波形 → 发送到 HeartVoice API 分析
     */
    fun startEcgMeasurement() {
        viewModelScope.launch {
            // 1. 采集 ECG 原始波形
            val ecgData = ecgCollector.startEcgCollection(targetDurationSec = 30)

            if (ecgData.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error("ECG 采集失败或无数据")
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                ecgState = EcgCollectionState.Analyzing,
                ecgWaveform = ecgData,
            )

            // 2. 发送到 HeartVoice API 分析
            if (apiKey.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    ecgState = EcgCollectionState.Error("未配置 HeartVoice API Key")
                )
                return@launch
            }

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
            ecgWaveform = emptyList(),
        )
    }

    override fun onCleared() {
        analyzer.close()
        ecgCollector.disconnect()
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 60
    }
}
