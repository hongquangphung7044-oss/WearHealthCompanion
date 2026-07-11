package com.wearhealth.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.wearhealth.companion.model.HealthStatus
import com.wearhealth.companion.model.toDisplayText

@Composable
fun HealthMonitorScreen(viewModel: HealthViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "健康伴侣",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
            )
        }

        // 当前状态
        item {
            StatusCard(status = uiState.status)
        }

        // 实时数据
        item {
            MetricsGrid(sample = uiState.latestSample)
        }

        // 控制按钮
        item {
            Button(
                onClick = {
                    if (uiState.isMonitoring) viewModel.stopMonitoring()
                    else viewModel.startMonitoring()
                },
            ) {
                Text(if (uiState.isMonitoring) "停止" else "开始")
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
        modifier = Modifier.padding(8.dp),
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MetricRow(label = "心率", value = if (sample.heartRate > 0) "${sample.heartRate} bpm" else "--")
        MetricRow(label = "HRV", value = if (sample.hrvMs > 0) "${"%.0f".format(sample.hrvMs)} ms" else "--")
        MetricRow(label = "血氧", value = if (sample.spo2 > 0) "${sample.spo2}%" else "--")
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    androidx.wear.compose.material.Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = Color(0xFFB0BEC5),
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            color = Color.White,
        )
    }
}
