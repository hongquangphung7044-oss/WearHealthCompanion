package com.wearhealth.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wearhealth.companion.data.EcgHistoryRepository
import com.wearhealth.companion.data.HistoryItem
import com.wearhealth.companion.model.EcgCollectionState
import com.wearhealth.companion.model.diagnosisLabelToText
import com.wearhealth.companion.model.diagnosisSummary
import com.wearhealth.companion.model.hasDiagnosisConflict
import com.wearhealth.companion.model.isDiagnosisSerious
import com.wearhealth.companion.model.toParamInfos

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
            Text(text = statusText, style = MaterialTheme.typography.bodySmall, color = color)
        }

        val state = uiState.ecgState

        // 预热中提示
        if (state is EcgCollectionState.Connecting) {
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

        // 采集倒计时 + 实时波形（显示 1 秒数据，能看到心跳周期）
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
                EcgWaveform(
                    samples = uiState.liveSamples,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
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

        // 空闲引导
        if (state is EcgCollectionState.Idle && uiState.sdkAvailable && !uiState.showHistory) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text("测量步骤", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("1. 手表戴紧手腕", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("2. 手指轻触上方按键\n（不要按下去）", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                    Text("3. 保持 30 秒不动", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface)
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

        // 测量结果
        if (state is EcgCollectionState.Done && uiState.ecgResult != null) {
            item { EcgResultCard(result = uiState.ecgResult!!) }
        }

        // 历史记录界面
        if (uiState.showHistory) {
            item {
                Text(
                    text = "测量历史",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (uiState.history.isEmpty()) {
                item {
                    Text(
                        text = "暂无历史记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0BEC5),
                    )
                }
            } else {
                items(uiState.history) { item ->
                    HistoryItemRow(
                        item = item,
                        onDelete = { viewModel.deleteHistory(item.timestamp) },
                    )
                }
                item {
                    Button(
                        onClick = { viewModel.deleteAllHistory() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    ) { Text("清空全部", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Button(onClick = { viewModel.hideHistory() }) {
                    Text("返回", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 底部按钮
        if (!uiState.showHistory) {
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
                    onClick = { if (canMeasure) viewModel.startEcgMeasurement() },
                    enabled = canMeasure && uiState.sdkAvailable,
                ) { Text(buttonText) }
            }
            // 历史记录入口
            item {
                Button(onClick = { viewModel.showHistory() }) {
                    Text("历史记录", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 底部免责声明（常驻）
        item {
            Text(
                text = "本应用仅供参考，不能替代医生诊断。\n如有不适请及时就医。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF607D8B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * ECG 波形绘制（Canvas）
 *
 * 关键：先去基线（减均值），再用动态范围自适应缩放。
 */
@Composable
private fun EcgWaveform(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4CAF50),
) {
    Canvas(
        modifier = modifier.background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp)),
    ) {
        if (samples.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // 去基线
        val mean = samples.average()
        val centered = samples.map { (it - mean).toFloat() }
        // 自适应缩放
        val maxVal = centered.maxOrNull() ?: 1f
        val minVal = centered.minOrNull() ?: -1f
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val scale = (h * 0.75f) / range

        val path = Path()
        val stepX = if (samples.size > 1) w / (samples.size - 1) else w
        for (i in centered.indices) {
            val x = i * stepX
            val y = midY - (centered[i] * scale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))
        // 基线
        drawLine(
            color = Color(0xFF334155),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1f,
        )
    }
}

@Composable
private fun EcgResultCard(result: com.wearhealth.companion.model.EcgAnalysisResult) {
    val hasSerious = result.diagnosis.any { isDiagnosisSerious(it) }
    val summaryColor = if (hasSerious) Color(0xFFEF5350) else Color(0xFF4CAF50)
    val conflict = hasDiagnosisConflict(result)

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ECG 波形（全局缩略图）
        if (result.ecgSamples.isNotEmpty()) {
            EcgWaveform(
                samples = result.ecgSamples,
                modifier = Modifier.fillMaxWidth().height(70.dp),
                color = Color(0xFF4CAF50),
            )
            Text(
                text = "（30 秒心电图缩略）",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C),
            )
        }

        // 通俗解读
        Text(
            text = diagnosisSummary(result.diagnosis),
            style = MaterialTheme.typography.titleSmall,
            color = summaryColor,
            textAlign = TextAlign.Center,
        )

        // 诊断明细
        if (result.diagnosis.isNotEmpty()) {
            Text(
                text = "诊断: " + result.diagnosis.joinToString("、") { diagnosisLabelToText(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        // 矛盾提示（WPW 等）
        if (conflict != null) {
            Text(
                text = "⚠️ $conflict",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                textAlign = TextAlign.Center,
            )
        }

        // 信号质量
        Text(
            text = "信号质量: ${"%.0f%%".format(result.signalQuality * 100)}" +
                    if (result.signalQuality < 0.7) "（偏低，建议重测）" else "（良好）",
            style = MaterialTheme.typography.bodySmall,
            color = if (result.signalQuality >= 0.7) Color(0xFF4CAF50) else Color(0xFFFF9800),
        )

        // 详细参数
        val params = result.toParamInfos()
        if (params.isNotEmpty()) {
            Text("—— 详细参数 ——", style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF607D8B))
            params.forEach { p ->
                Text("${p.label}: ${p.value}（正常 ${p.normal}）",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
                Text(p.desc, style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78909C), textAlign = TextAlign.Center)
            }
        }

        // 早搏
        if (result.pacCount > 0 || result.pvcCount > 0) {
            Text(
                text = "房早 ${result.pacCount} 次 / 室早 ${result.pvcCount} 次",
                style = MaterialTheme.typography.bodySmall,
                color = if (result.pacCount + result.pvcCount > 10) Color(0xFFEF5350) else Color(0xFFFF9800),
            )
            Text("（偶发早搏正常，频繁需就医）",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF78909C))
        }
    }
}

@Composable
private fun HistoryItemRow(item: HistoryItem, onDelete: () -> Unit) {
    val diagColor = if (item.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = EcgHistoryRepository.formatTime(item.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0BEC5),
        )
        // 缩略波形
        if (item.ecgSamples.isNotEmpty()) {
            EcgWaveform(
                samples = item.ecgSamples,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = diagColor,
            )
        }
        Text(
            text = item.diagnosis.joinToString("、") { diagnosisLabelToText(it) },
            style = MaterialTheme.typography.bodySmall,
            color = diagColor,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "心率 ${item.avgHeartRate} bpm | 质量 ${"%.0f%%".format(item.signalQuality * 100)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF78909C),
        )
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text("删除", style = MaterialTheme.typography.bodySmall)
        }
    }
}
