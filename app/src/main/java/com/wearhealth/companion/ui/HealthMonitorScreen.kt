package com.wearhealth.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Box(modifier = modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
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

        // API Key 输入界面：始终显示输入卡（未配置时提示"请配置"，已配置时提示"更新"）。
        if (!uiState.showHistory) {
            item {
                ApiKeyInputCard(
                    alreadyConfigured = uiState.apiKeyConfigured,
                    onSave = { key -> viewModel.saveApiKey(key) },
                    onFetchFromPhone = { viewModel.fetchApiKeyFromPhone() },
                    syncing = uiState.syncingToPhone,
                )
            }
            // DeepSeek 配置卡片（紧凑，折叠式）
            item {
                DeepSeekConfigCard(
                    configured = uiState.dsApiKeyConfigured,
                    balanceText = uiState.dsBalanceText,
                    balanceLoading = uiState.dsBalanceLoading,
                    balanceError = uiState.dsBalanceError,
                    onSave = { viewModel.saveDeepSeekApiKey(it) },
                    onQueryBalance = { viewModel.queryDeepSeekBalance() },
                )
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
            // 分析方式选择条（3 档横向紧凑布局）
            item {
                AnalysisMethodSelector(
                    selected = uiState.analysisMethod,
                    dsConfigured = uiState.dsApiKeyConfigured,
                    onSelect = { viewModel.setAnalysisMethod(it) },
                )
            }
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
                    onClick = {
                        if (canMeasure) {
                            // DS 方式需要先收集年龄性别（若未配置）
                            if (uiState.analysisMethod.startsWith("ds_")) {
                                viewModel.setShowPreMeasureDialog(true)
                            } else {
                                viewModel.startEcgMeasurement(uiState.analysisMethod)
                            }
                        }
                    },
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
        } // 关闭 ScalingLazyColumn

        // 测量前年龄性别二级界面（DS 方式触发，全屏覆盖，非弹窗）
        if (uiState.showPreMeasureDialog) {
            PreMeasureScreen(
                onConfirm = { age, isMale ->
                    viewModel.saveUserAge(age)
                    viewModel.saveUserGender(isMale)
                    viewModel.setShowPreMeasureDialog(false)
                    viewModel.startEcgMeasurement(uiState.analysisMethod)
                },
                onDismiss = { viewModel.setShowPreMeasureDialog(false) },
            )
        }
    } // 关闭 Box
}

/**
 * ECG 波形绘制（Canvas）
 *
 * 关键：先去基线（减均值），再用固定电压比例绘制（不再自适应拉伸）。
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
        // 固定电压比例：图表高度代表 ±2mV 窗口（4mV 总量），不再自适应拉伸
        val yScale = h / (4f * 1000f)

        val path = Path()
        val stepX = if (samples.size > 1) w / (samples.size - 1) else w
        for (i in centered.indices) {
            val x = i * stepX
            val y = (midY - (centered[i] * yScale)).coerceIn(0f, h)
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

        // DeepSeek AI 解读报告（仅 DS 分析方式）
        if (result.aiReport.isNotEmpty()) {
            DeepSeekReportCard(reportJson = result.aiReport)
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
        // 分析方式标记 + DS 报告
        if (item.analysisMethod != "heartvoice") {
            Text(
                text = "分析方式: " + when (item.analysisMethod) {
                    "ds_flash_fast" -> "DS闪速"
                    "ds_flash_balanced" -> "DS深度"
                    "ds_pro_max" -> "DS Pro Max"
                    else -> item.analysisMethod
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9C27B0),
            )
        }
        if (item.aiReport.isNotEmpty()) {
            DeepSeekReportCard(reportJson = item.aiReport)
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
    alreadyConfigured: Boolean,
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
            text = if (alreadyConfigured) "更新 API Key" else "请配置 API Key",
            style = MaterialTheme.typography.titleSmall,
            color = if (alreadyConfigured) Color(0xFF4CAF50) else Color(0xFFFF9800),
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

/**
 * DeepSeek 配置卡片（紧凑折叠式）
 *
 * 与 HeartVoice API Key 卡片平级，显示 DS Key 状态 + 余额查询 + Key 输入。
 */
@Composable
private fun DeepSeekConfigCard(
    configured: Boolean,
    balanceText: String?,
    balanceLoading: Boolean,
    balanceError: String?,
    onSave: (String) -> Unit,
    onQueryBalance: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var dsKeyInput by remember { mutableStateOf("") }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        // 折叠头：点击展开/收起
        Button(
            onClick = { expanded = !expanded },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A1A2E),
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "DeepSeek 设置 " + if (configured) "✓" else "（未配置）",
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) Color(0xFF9C27B0) else Color(0xFFFF9800),
            )
        }
        // 余额行（已配置时显示）
        if (configured) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "余额: " + (balanceText ?: "未查询"),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0BEC5),
                )
                Button(
                    onClick = onQueryBalance,
                    enabled = !balanceLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                ) {
                    Text(
                        if (balanceLoading) "查询中…" else "刷新",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                    )
                }
            }
            balanceError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF5350))
            }
        }
        // 展开后显示 Key 输入框
        if (expanded) {
            BasicTextField(
                value = dsKeyInput,
                onValueChange = { dsKeyInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                cursorBrush = SolidColor(Color(0xFF9C27B0)),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                decorationBox = { inner ->
                    if (dsKeyInput.isEmpty()) {
                        Text(
                            text = "输入 DeepSeek API Key",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF78909C),
                        )
                    }
                    inner()
                },
            )
            Button(
                onClick = {
                    onSave(dsKeyInput)
                    dsKeyInput = ""
                    expanded = false
                },
                enabled = dsKeyInput.isNotBlank(),
            ) {
                Text("保存 DS Key", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "可从手机端下发，或在此输入",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 分析方式选择条（3 档横向紧凑布局）
 * DS 未配置 Key 时仍可选，但测量时会提示先配置。
 */
@Composable
private fun AnalysisMethodSelector(
    selected: String,
    dsConfigured: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
    ) {
        Text(
            text = "分析方式",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF78909C),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val options = listOf(
                Triple("heartvoice", "专业API", Color(0xFF4CAF50)),
                Triple("ds_flash_fast", "DS闪速", Color(0xFF64B5F6)),
                Triple("ds_flash_balanced", "DS深度", Color(0xFF9C27B0)),
            )
            options.forEach { (method, label, color) ->
                val isSelected = selected == method
                Button(
                    onClick = { onSelect(method) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) color else Color(0xFF263238),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        if (!dsConfigured && selected.startsWith("ds_")) {
            Text(
                text = "⚠️ 未配置 DS Key，请先在设置页填写",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 测量前年龄性别二级界面（DS 方式触发）
 *
 * 全屏 ScalingLazyColumn 页面（与主屏同风格），非弹窗，避免圆表内容拥挤。
 * 收集用户年龄和性别，影响 QTc/HRV 判断阈值。
 * 年龄可跳过（填 0），性别可跳过（选"不提供"）。
 */
@Composable
private fun PreMeasureScreen(
    onConfirm: (age: Int, isMale: Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    var ageText by remember { mutableStateOf("") }
    var genderSelected by remember { mutableStateOf(0) } // 0=未选, 1=男, 2=女, 3=不提供
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "补充信息（可选）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            Text(
                text = "年龄性别影响 QTc/HRV 阈值，可跳过",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C),
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text("年龄", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5))
        }
        item {
            BasicTextField(
                value = ageText,
                onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color(0xFF64B5F6)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { inner ->
                    if (ageText.isEmpty()) {
                        Text("填写年龄（可空）", style = MaterialTheme.typography.bodySmall, color = Color(0xFF78909C))
                    }
                    inner()
                },
            )
        }
        item {
            Text("性别", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB0BEC5))
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf("男" to 1, "女" to 2, "不提供" to 3).forEach { (label, value) ->
                    Button(
                        onClick = { genderSelected = value },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (genderSelected == value) Color(0xFF64B5F6) else Color(0xFF263238),
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消", style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Button(
                onClick = {
                    val age = ageText.toIntOrNull() ?: 0
                    val isMale = when (genderSelected) {
                        1 -> true
                        2 -> false
                        else -> null
                    }
                    onConfirm(age, isMale)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开始测量", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * DeepSeek AI 解读报告卡片
 *
 * 解析 DS 返回的 JSON 报告，在手表上展示关键字段：
 * - 综合解读（2-4 句中文）
 * - 节律判断 + 置信度
 * - 心率变异性解读
 * - 异常提示
 * - 健康建议
 */
@Composable
private fun DeepSeekReportCard(reportJson: String) {
    // 解析 JSON（容错：先剥离 Markdown 包裹，解析失败显示原文摘要）
    val parsed: Map<String, String> = remember(reportJson) {
        try {
            val cleaned = com.wearhealth.companion.shared.JsonCleaner.extractJsonObject(reportJson)
            val json = org.json.JSONObject(cleaned)
            val rhythm = json.optJSONObject("节律分析")
            val hrv = json.optJSONObject("心率分析")?.optJSONObject("心率变异性")
            val abnormals = json.optJSONArray("异常提示")
            val advice = json.optJSONArray("健康建议")
            val abnormalText = if (abnormals != null) {
                (0 until abnormals.length()).joinToString("；") { abnormals.optString(it) }
            } else ""
            val adviceText = if (advice != null) {
                (0 until advice.length()).joinToString("；") { advice.optString(it) }
            } else ""
            mapOf(
                "综合解读" to (json.optString("综合解读", "")),
                "节律判断" to (rhythm?.optString("节律判断", "") ?: ""),
                "节律置信度" to (rhythm?.optString("置信度", "") ?: ""),
                "HRV解读" to (hrv?.optString("解读", "") ?: ""),
                "异常提示" to abnormalText,
                "健康建议" to adviceText,
            )
        } catch (e: Exception) {
            mapOf("综合解读" to reportJson.take(200))
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text("—— DeepSeek AI 解读 ——",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFF9C27B0))
        parsed["综合解读"]?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE1BEE7), textAlign = TextAlign.Center)
        }
        parsed["节律判断"]?.takeIf { it.isNotBlank() }?.let {
            Text("节律: $it" +
                (parsed["节律置信度"]?.takeIf { c -> c.isNotBlank() }?.let { " (置信度:$it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0BEC5))
        }
        parsed["HRV解读"]?.takeIf { it.isNotBlank() }?.let {
            Text("HRV: $it", style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0BEC5), textAlign = TextAlign.Center)
        }
        parsed["异常提示"]?.takeIf { it.isNotBlank() }?.let {
            Text("⚠️ $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
        }
        parsed["健康建议"]?.takeIf { it.isNotBlank() }?.let {
            Text("建议: $it", style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78909C), textAlign = TextAlign.Center)
        }
    }
}
