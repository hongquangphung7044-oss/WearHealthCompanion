package com.wearhealth.companion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测量详情页
 *
 * - 大屏完整 ECG 波形图（可交互缩放）
 * - 诊断结果（中文化 + 通俗解读）
 * - 全部参数详情（心率、QRS、PR、QT、QTc、早搏）
 * - 信号质量
 * - 底部导出 PDF 按钮 + 免责声明
 *
 * @param measurementId 记录主键
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    viewModel: MobileViewModel,
    measurementId: Long,
    onBack: () -> Unit,
) {
    var transfer by remember { mutableStateOf<EcgMeasurementTransfer?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(measurementId) {
        transfer = viewModel.loadMeasurement(measurementId)
        loaded = true
    }

    val exporting by viewModel.exporting.collectAsState()
    val pdfResult by viewModel.pdfExportResult.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            transfer == null -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center) {
                    Text("记录不存在", color = MaterialTheme.colorScheme.error)
                }
            }
            else -> DetailContent(
                data = transfer!!,
                modifier = Modifier.padding(padding).fillMaxSize(),
                exporting = exporting,
                pdfResult = pdfResult,
                onExport = { viewModel.exportPdf(transfer!!) },
            )
        }
    }
}

@Composable
private fun DetailContent(
    data: EcgMeasurementTransfer,
    modifier: Modifier,
    exporting: Boolean,
    pdfResult: String?,
    onExport: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ===== 标题与时间 =====
        Text(
            text = formatTime(data.timestamp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ===== 完整 ECG 波形图 =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "心电图波形（双指缩放、单指拖动查看）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
                MobileEcgChart(
                    samples = data.rawEcgData,
                    modifier = Modifier.fillMaxWidth(),
                    interactive = true,
                )
            }
        }

        // ===== 诊断结果 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (data.isAbnormal) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) else CardDefaults.cardColors(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "诊断结果",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = diagnosisToText(data.diagnosis),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (data.isAbnormal) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = diagnosisSummary(data.diagnosis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ===== 参数详情表 =====
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "参数详情",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                ParamRow("平均心率", "${data.avgHeartRate} bpm", "60-100")
                if (data.minHeartRate > 0 && data.maxHeartRate > 0) {
                    ParamRow("心率范围", "${data.minHeartRate} ~ ${data.maxHeartRate} bpm", "60-100")
                }
                if (data.avgQrs > 0) {
                    ParamRow("QRS 宽度", "${data.avgQrs} ms", "80-120")
                }
                if (data.prInterval > 0) {
                    ParamRow("PR 间期", "${data.prInterval} ms", "120-200")
                }
                if (data.avgQt > 0) {
                    ParamRow("QT 间期", "${data.avgQt} ms", "随心率变化")
                }
                if (data.avgQtc > 0) {
                    ParamRow("QTc", "${data.avgQtc} ms", "男<450 / 女<460")
                }
                ParamRow("房性早搏", "${data.pacCount} 次", "0")
                ParamRow("室性早搏", "${data.pvcCount} 次", "0")
                ParamRow("采样率", "${data.sampleRate} Hz", "-")
            }
        }

        // ===== 信号质量 =====
        val sq = data.signalQuality
        val sqColor = if (sq >= 0.7) Color(0xFF388E3C)
                      else if (sq >= 0.4) Color(0xFFF57C00)
                      else MaterialTheme.colorScheme.error
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("信号质量", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${(sq * 100).toInt()}%" +
                            if (sq >= 0.7) "（良好，结果可信）"
                            else if (sq >= 0.4) "（一般，结果仅供参考）"
                            else "（较差，建议重新测量）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sqColor,
                )
            }
        }

        // ===== 导出 PDF =====
        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !exporting,
        ) {
            if (exporting) {
                Text("导出中...")
            } else {
                Text("导出 PDF 报告")
            }
        }
        pdfResult?.let { path ->
            Text(
                text = "已导出：$path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ===== 免责声明 =====
        Text(
            text = "本报告由 AI 自动分析生成，仅供健康参考，不作为诊断依据。" +
                    "如有异常请咨询专业医生。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** 参数行（标签 / 值 / 正常范围） */
@Composable
private fun ParamRow(label: String, value: String, normal: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "正常 $normal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

/** 格式化时间戳 */
private fun formatTime(ts: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(ts))
}
