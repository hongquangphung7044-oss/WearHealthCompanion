package com.wearhealth.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.diagnosisLabelToText

@Composable
fun HealthMonitorScreen(
    viewModel: HealthViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = modifier,
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
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

        // 状态行
        item {
            val sdkOk = uiState.sdkAvailable
            val apiOk = uiState.apiKeyConfigured
            val statusText = when {
                !sdkOk -> "缺 Samsung SDK"
                !apiOk -> "缺 API Key"
                else -> "系统就绪"
            }
            val color = if (sdkOk && apiOk) Color(0xFF4CAF50) else Color(0xFFFF9800)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }

        // 预热中提示（Connecting 状态）
        if (uiState.ecgState is EcgCollectionState.Connecting) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "预热中...",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF64B5F6),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "请将手指轻触\n上方按键（不要按下）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // 采集状态 + 倒计时 + 实时波形
        val state = uiState.ecgState
        if (state is EcgCollectionState.Collecting) {
            item {
                Text(
                    text = "倒计时 ${state.countdownSec}s",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFF9800),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = "${state.samplesCollected} 点",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0BEC5),
                )
            }
            // 实时 ECG 波形
            item {
                EcgWaveform(
                    samples = uiState.liveSamples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    color = Color(0xFF4CAF50),
                )
            }
        }

        // 分析中
        if (state is EcgCollectionState.Analyzing) {
            item {
                Text(
                    text = "AI 分析中...",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF64B5F6),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 空闲时显示用户引导
        if (state is EcgCollectionState.Idle && uiState.sdkAvailable) {
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
                        text = "2. 手指轻触上方按键\n（不要按下去）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "3. 保持 30 秒不动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // 错误提示
        if (state is EcgCollectionState.Error) {
            item {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFEF5350),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 测量完成：结果 + 波形
        if (state is EcgCollectionState.Done && uiState.ecgResult != null) {
            item {
                EcgResultCard(result = uiState.ecgResult!!)
            }
        }

        // 操作按钮
        item {
            val canMeasure = state is EcgCollectionState.Idle ||
                    state is EcgCollectionState.Error ||
                    state is EcgCollectionState.Done
            val buttonText = when (state) {
                is EcgCollectionState.Idle -> "开始测量"
                is EcgCollectionState.Error -> "重试"
                is EcgCollectionState.Done -> "再测一次"
                is EcgCollectionState.Connecting -> "预热中..."
                is EcgCollectionState.Collecting -> "采集中"
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

/**
 * ECG 波形绘制（Canvas）
 */
@Composable
private fun EcgWaveform(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4CAF50),
) {
    Canvas(
        modifier = modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp)),
    ) {
        if (samples.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val midY = h / 2f

        // 计算缩放
        val maxAbs = samples.maxOfOrNull { kotlin.math.abs(it) }?.toFloat() ?: 1f
        val scale = if (maxAbs > 0) (h * 0.4f) / maxAbs else 1f

        val path = Path()
        val stepX = if (samples.size > 1) w / (samples.size - 1) else w
        for (i in samples.indices) {
            val x = i * stepX
            val y = midY - (samples[i] * scale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun EcgResultCard(result: com.wearhealth.companion.model.EcgAnalysisResult) {
    val diagColor = if (result.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ECG 波形
        if (result.ecgSamples.isNotEmpty()) {
            EcgWaveform(
                samples = result.ecgSamples,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp),
                color = Color(0xFF4CAF50),
            )
        }
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
