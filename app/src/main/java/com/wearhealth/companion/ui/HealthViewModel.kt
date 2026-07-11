package com.wearhealth.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wearhealth.companion.analysis.TFLiteAnalyzer
import com.wearhealth.companion.model.HealthSample
import com.wearhealth.companion.model.HealthStatus
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
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val collector = HealthSensorCollector(app.applicationContext)
    private val analyzer = TFLiteAnalyzer(app.applicationContext)

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

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

    override fun onCleared() {
        analyzer.close()
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORY = 60
    }
}
