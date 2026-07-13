package com.wearhealth.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.HealthStatus
import com.wearhealth.companion.model.diagnosisLabelToText
import com.wearhealth.companion.model.toDisplayText

@Composable
fun HealthMonitorScreen(viewModel: HealthViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 标题
        item {
            Text(
                text = "健康伴侣",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
            )
        }

        // 状态卡片
        item {
            StatusCard(status = uiState.status)
        }

        // 实时心率/HRV/血氧
        item {
            MetricsGrid(sample = uiState.latestSample)
        }

        // 监测开关
        item {
            Button(
                onClick = {
                    if (uiState.isMonitoring) viewModel.stopMonitoring()
                    else viewModel.startMonitoring()
                },
            ) {
                Text(if (uiState.isMonitoring) "停止监测" else "开始监测")
            }
        }

        // === ECG 测量区 ===
        item {
            Text(
                text = "── ECG 心电图 ──",
                style = MaterialTheme.typography.body2,
                color = Color(0xFF64B5F6),
            )
        }

        // SDK / API Key 状态
        item {
            val sdkOk = uiState.sdkAvailable
            val apiOk = uiState.apiKeyConfigured
            val statusText = when {
                sdkOk && apiOk -> "✓ 可用"
                !sdkOk -> "✗ 缺 Samsung SDK"
                !apiOk -> "✗ 缺 API Key"
                else -> ""
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.body2,
                color = if (sdkOk && apiOk) Color(0xFF4CAF50) else Color(0xFFFF9800),
            )
        }

        // ECG 采集状态
        item {
            EcgStateCard(state = uiState.ecgState)
        }

        // ECG 分析结果
        if (uiState.ecgResult != null) {
            item {
                EcgResultCard(result = uiState.ecgResult!!)
            }
        }

        // ECG 测量按钮
        item {
            val canMeasure = uiState.ecgState is EcgCollectionState.Idle ||
                    uiState.ecgState is EcgCollectionState.Error
            Button(
                onClick = {
                    if (canMeasure) viewModel.startEcgMeasurement()
                    else viewModel.resetEcg()
                },
                enabled = uiState.sdkAvailable,
            ) {
                Text(
                    when (uiState.ecgState) {
                        is EcgCollectionState.Idle -> "测心电图"
                        is EcgCollectionState.Error -> "重试"
                        is EcgCollectionState.Done -> "再测一次"
                        else -> "测量中..."
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: HealthStatus) {
    val color = when (status) {
        is HealthStatus.Normal -> Color(0xFF4CAF50)
        is HealthStatus.Unknown -> Color(0xFF9E9E9E)
        else -> Color(0xFFFF9800)
    }
    Box(
        modifier = Modifier.padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = status.toDisplayText(),
            color = color,
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MetricsGrid(sample: com.wearhealth.companion.model.HealthSample) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetricRow(label = "心率", value = if (sample.heartRate > 0) "${sample.heartRate}" else "--")
        MetricRow(label = "HRV", value = if (sample.hrvMs > 0) "${"%.0f".format(sample.hrvMs)}ms" else "--")
        MetricRow(label = "血氧", value = if (sample.spo2 > 0) "${sample.spo2}%" else "--")
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = Color(0xFFB0BEC5),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            color = Color.White,
        )
    }
}

@Composable
private fun EcgStateCard(state: EcgCollectionState) {
    val (text, color) = when (state) {
        is EcgCollectionState.Idle -> "就绪" to Color(0xFF9E9E9E)
        is EcgCollectionState.Connecting -> "连接中..." to Color(0xFF64B5F6)
        is EcgCollectionState.Collecting -> "采集中 ${state.samplesCollected}点" to Color(0xFFFF9800)
        is EcgCollectionState.Analyzing -> "AI分析中..." to Color(0xFF64B5F6)
        is EcgCollectionState.Done -> "完成" to Color(0xFF4CAF50)
        is EcgCollectionState.Error -> "错误: ${state.message}" to Color(0xFFEF5350)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EcgResultCard(result: com.wearhealth.companion.model.EcgAnalysisResult) {
    val diagColor = if (result.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 诊断结果
        Text(
            text = result.diagnosis.joinToString(" ") { diagnosisLabelToText(it) },
            style = MaterialTheme.typography.title3,
            color = diagColor,
            textAlign = TextAlign.Center,
        )
        // 信号质量
        Text(
            text = "质量: ${"%.0f%%".format(result.signalQuality * 100)}",
            style = MaterialTheme.typography.body2,
            color = if (result.signalQuality >= 0.7) Color(0xFF4CAF50) else Color(0xFFFF9800),
        )
        // 心率
        Text(
            text = "心率: ${result.avgHeartRate} bpm",
            style = MaterialTheme.typography.body1,
            color = Color.White,
        )
        // 间期参数
        if (result.avgQrs > 0) {
            Text(
                text = "QRS:${result.avgQrs} PR:${result.prInterval} QT:${result.avgQt}",
                style = MaterialTheme.typography.body2,
                color = Color(0xFFB0BEC5),
            )
        }
        // 早搏计数
        if (result.pacCount > 0 || result.pvcCount > 0) {
            Text(
                text = "房早:${result.pacCount} 室早:${result.pvcCount}",
                style = MaterialTheme.typography.body2,
                color = Color(0xFFFF9800),
            )
        }
    }
}
