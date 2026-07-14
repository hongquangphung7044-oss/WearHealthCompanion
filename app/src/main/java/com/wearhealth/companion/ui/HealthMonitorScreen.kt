package com.wearhealth.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
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

        // 同步状态消息（如果 syncMessage 不为 null）
        val syncMsg = uiState.syncMessage
        if (syncMsg != null) {
            item {
                val msgColor = if (syncMsg.startsWith("传送失败") ||
                    syncMsg.startsWith("BLE 传送失败") ||
                    syncMsg.startsWith("自动传输失败") ||
                    syncMsg.startsWith("API Key 不能为空"))
                    Color(0xFFEF5350) else Color(0xFF64B5F6)
                Text(
                    text = syncMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = msgColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // API Key 输入界面：未配置时显示完整输入卡；已有 Key 时仍保留 BLE 更新入口。
        if (!uiState.apiKeyConfigured && !uiState.showHistory) {
            item {
                ApiKeyInputCard(
                    onSave = { key -> viewModel.saveApiKey(key) },
                    onFetchFromPhone = { viewModel.fetchApiKeyFromPhone() },
                    syncing = uiState.syncingToPhone,
                )
            }
        } else if (uiState.apiKeyConfigured && !uiState.showHistory) {
            item {
                Button(
                    onClick = { viewModel.fetchApiKeyFromPhone() },
                    enabled = !uiState.syncingToPhone,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                ) {
                    Text(
                        if (uiState.syncingToPhone) "正在查找手机…" else "从手机 BLE 更新 Key",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        val state = uiState.ecgState

        // 连接中 / 等待电极接触
        if (state is EcgCollectionState.Connecting) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "连接中...",
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

        // 预热激活中（电极已接触，信号稳定期倒计时）
        if (state is EcgCollectionState.Preheating) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "预热激活 ${state.countdownSec}s",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFFF9800),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "信号稳定中\n请保持手指不动",
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
            // 历史详情页
            if (uiState.historyDetail != null) {
                item {
                    Text(
                        text = "测量详情",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item { HistoryDetailCard(item = uiState.historyDetail!!) }
                // 传送到手机按钮
                item {
                    val detail = uiState.historyDetail!!
                    Button(
                        onClick = { viewModel.syncToPhone(detail) },
                        enabled = !uiState.syncingToPhone,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (detail.syncedToPhone) Color(0xFF607D8B) else Color(0xFF64B5F6),
                        ),
                    ) {
                        Text(
                            when {
                                uiState.syncingToPhone -> "传送中..."
                                detail.syncedToPhone -> "重新传送"
                                else -> "传送到手机"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.deleteHistory(uiState.historyDetail!!.timestamp) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                    ) { Text("删除此记录", style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Button(onClick = { viewModel.hideHistoryDetail() }) {
                        Text("返回列表", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                // 历史列表
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
                    item {
                        Text(
                            text = "点击查看详情，长按删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF78909C),
                            textAlign = TextAlign.Center,
                        )
                    }
                    items(uiState.history) { item ->
                        HistoryItemRow(
                            item = item,
                            onClick = { viewModel.showHistoryDetail(item) },
                            onLongClick = { viewModel.deleteHistory(item.timestamp) },
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
                    is EcgCollectionState.Connecting -> "连接中..."
                    is EcgCollectionState.Preheating -> "预热激活中"
                    is EcgCollectionState.Collecting -> "采集中"
                    is EcgCollectionState.Analyzing -> "分析中..."
                }
                Button(
                    onClick = { if (canMeasure) viewModel.startEcgMeasurement() },
                    enabled = canMeasure && uiState.sdkAvailable,
                ) { Text(buttonText) }
            }
            item {
                Button(onClick = { viewModel.setAutoSyncEnabled(!uiState.autoSyncEnabled) }) {
                    Text(
                        if (uiState.autoSyncEnabled) "自动传输：已开启" else "自动传输：已关闭",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
        // ECG 波形（可交互：拖动+缩放）
        if (result.ecgSamples.isNotEmpty()) {
            InteractiveEcgChart(
                samples = result.ecgSamples,
                modifier = Modifier.fillMaxWidth().height(100.dp),
                color = Color(0xFF4CAF50),
                interactive = true,
            )
            Text(
                text = "（30 秒心电图，可拖动查看/双指缩放）",
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

        if (result.possibleDiagnoses.isNotEmpty()) {
            Text(
                "可能诊断: " + result.possibleDiagnoses.joinToString("、") { diagnosisLabelToText(it) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val diagColor = if (item.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 整个卡片可点击/长按
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = EcgHistoryRepository.formatTime(item.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5),
            )
            if (item.ecgSamples.isNotEmpty()) {
                InteractiveEcgChart(
                    samples = item.ecgSamples,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = diagColor,
                    interactive = false,
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
            // 同步状态指示
            val syncText = if (item.syncedToPhone) "已传送 ✓" else "未传送"
            val syncColor = if (item.syncedToPhone) Color(0xFF4CAF50) else Color(0xFFFF9800)
            Text(
                text = syncText,
                style = MaterialTheme.typography.bodySmall,
                color = syncColor,
            )
        }
    }
}

/**
 * 历史详情卡片：显示完整波形 + 所有参数
 */
@Composable
private fun HistoryDetailCard(item: HistoryItem) {
    val diagColor = if (item.isAbnormal) Color(0xFFEF5350) else Color(0xFF4CAF50)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = EcgHistoryRepository.formatTime(item.timestamp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0BEC5),
        )
        // 完整波形（可交互：拖动+缩放）
        if (item.ecgSamples.isNotEmpty()) {
            InteractiveEcgChart(
                samples = item.ecgSamples,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                color = diagColor,
                interactive = true,
            )
            Text(
                text = "（30 秒心电图，可拖动查看/双指缩放）",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C),
            )
        }
        // 诊断
        Text(
            text = diagnosisSummary(item.diagnosis),
            style = MaterialTheme.typography.titleSmall,
            color = diagColor,
            textAlign = TextAlign.Center,
        )
        if (item.diagnosis.isNotEmpty()) {
            Text(
                text = "诊断: " + item.diagnosis.joinToString("、") { diagnosisLabelToText(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        // 信号质量
        Text(
            text = "信号质量: ${"%.0f%%".format(item.signalQuality * 100)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (item.signalQuality >= 0.7) Color(0xFF4CAF50) else Color(0xFFFF9800),
        )
        // 心率
        if (item.avgHeartRate > 0) {
            Text("平均心率: ${item.avgHeartRate} bpm（正常 60-100）",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
            Text("30 秒测量期间的平均心跳次数",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF78909C))
        }
        Text(
            "最低心率: " + if (item.minHeartRate > 0) "${item.minHeartRate} bpm" else "暂无可靠估算",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0BEC5),
        )
        Text(
            "最高心率: " + if (item.maxHeartRate > 0) "${item.maxHeartRate} bpm" else "暂无可靠估算",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0BEC5),
        )
        Text(
            "最低/最高心率由有效 R-R 间期本地估算，不是 API 直接返回",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF78909C),
            textAlign = TextAlign.Center,
        )
        // 间期参数
        if (item.avgQrs > 0) {
            Text("QRS 宽度: ${item.avgQrs} ms（正常 80-120）",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
            Text("心室收缩时间，过宽可能传导问题",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF78909C))
        }
        if (item.prInterval > 0) {
            Text("PR 间期: ${item.prInterval} ms（正常 120-200）",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
        }
        if (item.avgQt > 0) {
            Text("QT 间期: ${item.avgQt} ms",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
        }
        if (item.avgQtc > 0) {
            Text("QTc: ${item.avgQtc} ms（男<450 / 女<460）",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
        }
        // 早搏
        if (item.pacCount > 0 || item.pvcCount > 0) {
            Text("房早 ${item.pacCount} 次 / 室早 ${item.pvcCount} 次",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.pacCount + item.pvcCount > 10) Color(0xFFEF5350) else Color(0xFFFF9800))
        }
    }
}

/**
 * API Key 输入卡片
 *
 * 当 apiKeyConfigured 为 false 时显示。用户可以直接在手表输入，也可以在手机端先保存
 * HeartVoice API Key，再从已配对手机的 BLE GATT 服务主动读取，不依赖历史记录或 GMS。
 */
@Composable
private fun ApiKeyInputCard(
    onSave: (String) -> Unit,
    onFetchFromPhone: () -> Unit,
    syncing: Boolean,
) {
    var apiKeyInput by remember { mutableStateOf("") }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = "请配置 API Key",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFF9800),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "在手机端 App 下发，或在此输入",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF78909C),
            textAlign = TextAlign.Center,
        )
        // 输入框（使用 BasicTextField，Wear OS 上会唤起输入法）
        BasicTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(Color(0xFF64B5F6)),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            decorationBox = { innerTextField ->
                if (apiKeyInput.isEmpty()) {
                    Text(
                        text = "输入 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF78909C),
                    )
                }
                innerTextField()
            },
        )
        Button(
            onClick = onFetchFromPhone,
            enabled = !syncing,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
        ) {
            Text(if (syncing) "正在查找手机…" else "从手机 BLE 获取", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "请先在手机 ECG 同步器中保存 Key，并开启后台同步或保持 App 前台",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF78909C),
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { onSave(apiKeyInput) },
            enabled = apiKeyInput.isNotBlank() && !syncing,
        ) {
            Text("保存", style = MaterialTheme.typography.bodySmall)
        }
    }
}
