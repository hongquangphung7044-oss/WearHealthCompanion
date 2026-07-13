package com.wearhealth.companion.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.diagnosisLabelToText

@Composable
fun HealthMonitorScreen(viewModel: HealthViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题
        item {
            Text(
                text = "心电图 ECG",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // SDK / API Key 状态
        item {
            val sdkOk = uiState.sdkAvailable
            val apiOk = uiState.apiKeyConfigured
            val statusText = when {
                sdkOk && apiOk -> "系统就绪"
                !sdkOk -> "缺 Samsung SDK"
                !apiOk -> "缺 API Key"
                else -> ""
            }
            val color = when {
                sdkOk && apiOk -> Color(0xFF4CAF50)
                else -> Color(0xFFFF9800)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }

        // 空闲时显示用户引导
        if (uiState.ecgState is EcgCollectionState.Idle && uiState.sdkAvailable) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = "测量步骤",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "1. 手表戴紧手腕",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "2. 另一只手食指\n轻触上方按键",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "3. 保持30秒不动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "注意：轻触即可，\n不要按下去",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                        textAlign = TextAlign.Center,
                    )
                }
            }
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

        // 操作按钮
        item {
            val canMeasure = uiState.ecgState is EcgCollectionState.Idle ||
                    uiState.ecgState is EcgCollectionState.Error ||
                    uiState.ecgState is EcgCollectionState.Done
            val buttonText = when (uiState.ecgState) {
                is EcgCollectionState.Idle -> "开始测量"
                is EcgCollectionState.Error -> "重试"
                is EcgCollectionState.Done -> "再测一次"
                is EcgCollectionState.Connecting -> "连接中..."
                is EcgCollectionState.Collecting -> "采集中 ${uiState.ecgState.let { (it as EcgCollectionState.Collecting).samplesCollected }}点"
                is EcgCollectionState.Analyzing -> "分析中..."
            }
            Button(
                onClick = {
                    if (canMeasure) viewModel.startEcgMeasurement()
                },
                enabled = canMeasure && uiState.sdkAvailable,
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun EcgStateCard(state: EcgCollectionState) {
    val (text, color) = when (state) {
        is EcgCollectionState.Idle -> "就绪" to Color(0xFF9E9E9E)
        is EcgCollectionState.Connecting -> "连接传感器..." to Color(0xFF64B5F6)
        is EcgCollectionState.Collecting -> "采集中 ${state.samplesCollected} 点" to Color(0xFFFF9800)
        is EcgCollectionState.Analyzing -> "AI 分析中..." to Color(0xFF64B5F6)
        is EcgCollectionState.Done -> "测量完成" to Color(0xFF4CAF50)
        is EcgCollectionState.Error -> "错误: ${state.message}" to Color(0xFFEF5350)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EcgResultCard(result: com.wearhealth.companion.model.EcgAnalysisResult) {
    val diagColor = if (result.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 诊断结果
        Text(
            text = result.diagnosis.joinToString(" ") { diagnosisLabelToText(it) },
            style = MaterialTheme.typography.titleSmall,
            color = diagColor,
            textAlign = TextAlign.Center,
        )
        // 心率
        if (result.avgHeartRate > 0) {
            Text(
                text = "心率: ${result.avgHeartRate} bpm",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // 信号质量
        Text(
            text = "信号质量: ${"%.0f%%".format(result.signalQuality * 100)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (result.signalQuality >= 0.7) Color(0xFF4CAF50) else Color(0xFFFF9800),
        )
        // 间期参数
        if (result.avgQrs > 0) {
            Text(
                text = "QRS:${result.avgQrs}ms  PR:${result.prInterval}ms",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
            )
            Text(
                text = "QT:${result.avgQt}ms  QTc:${result.avgQtc}ms",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
            )
        }
        // 早搏计数
        if (result.pacCount > 0 || result.pvcCount > 0) {
            Text(
                text = "房早:${result.pacCount}  室早:${result.pvcCount}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
            )
        }
    }
}
