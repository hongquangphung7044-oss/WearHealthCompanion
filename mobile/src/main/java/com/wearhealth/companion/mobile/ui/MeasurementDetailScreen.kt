package com.wearhealth.companion.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wearhealth.companion.mobile.pdf.PdfExporter
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    viewModel: MobileViewModel,
    measurementId: Long,
    onBack: () -> Unit,
) {
    var transfer by remember { mutableStateOf<EcgMeasurementTransfer?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(measurementId) {
        viewModel.clearMessages()
        transfer = viewModel.loadMeasurement(measurementId)
        loaded = true
    }

    val exporting by viewModel.exporting.collectAsState()
    val pdfResult by viewModel.pdfExportResult.collectAsState()
    val pdfError by viewModel.pdfExportError.collectAsState()
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) transfer?.let { viewModel.exportPdf(it, uri) }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除手机记录？") },
            text = {
                Text("此操作只删除手机 Room 中的这条记录，不会删除手表历史。删除后无法在手机端恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.delete(measurementId, onDeleted = onBack)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmation = false }) {
                    Text("取消")
                }
            },
        )
    }

    fun export() {
        val data = transfer ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viewModel.exportPdf(data)
        } else {
            createDocument.launch(PdfExporter.displayName(data.timestamp))
        }
    }

    fun openPdf() {
        val uri = pdfResult?.uri ?: return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            openError = null
        } catch (_: ActivityNotFoundException) {
            openError = "未找到可打开 PDF 的应用，请从系统文件管理器中查看"
        } catch (error: Exception) {
            openError = "无法打开 PDF：${error.message ?: "系统限制"}"
        }
    }

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
            !loaded -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            transfer == null -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("记录不存在", color = MaterialTheme.colorScheme.error) }
            else -> DetailContent(
                data = transfer!!,
                modifier = Modifier.padding(padding).fillMaxSize(),
                exporting = exporting,
                pdfLocation = pdfResult?.locationLabel,
                pdfError = pdfError ?: openError,
                onExport = ::export,
                onOpenPdf = ::openPdf,
                onDelete = { showDeleteConfirmation = true },
            )
        }
    }
}

@Composable
private fun DetailContent(
    data: EcgMeasurementTransfer,
    modifier: Modifier,
    exporting: Boolean,
    pdfLocation: String?,
    pdfError: String?,
    onExport: () -> Unit,
    onOpenPdf: () -> Unit,
    onDelete: () -> Unit,
) {
    val duration = if (data.sampleRate > 0) data.rawEcgData.size.toDouble() / data.sampleRate else 0.0
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = formatTime(data.timestamp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    "单导联心电图波形（双指缩放、单指拖动）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
                Text(
                    "${data.rawEcgData.size} 点 / ${data.sampleRate} Hz / ${"%.1f".format(duration)} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                MobileEcgChart(
                    samples = data.rawEcgData,
                    modifier = Modifier.fillMaxWidth(),
                    interactive = true,
                )
                Text(
                    "当前为单导联波形概览，振幅会自适应缩放；可用于观察趋势，不是标准诊断心电图纸。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (data.isAbnormal) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) else CardDefaults.cardColors(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("诊断结果", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    diagnosisToText(data.diagnosis),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (data.isAbnormal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    diagnosisSummary(data.diagnosis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (data.possibleDiagnoses.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可能诊断（API 提示）：${diagnosisToText(data.possibleDiagnoses)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("参数详情", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ParamRow("API 异常标志", if (data.isAbnormal) "异常" else "未标记异常", "API 输出")
                ParamRow("平均心率", "${data.avgHeartRate} bpm", "60-100")
                ParamRow(
                    "最低心率",
                    if (data.minHeartRate > 0) "${data.minHeartRate} bpm" else "暂无可靠估算",
                    "本地趋势参考",
                )
                ParamRow(
                    "最高心率",
                    if (data.maxHeartRate > 0) "${data.maxHeartRate} bpm" else "暂无可靠估算",
                    "本地趋势参考",
                )
                if (data.avgP > 0) ParamRow("平均 P 波宽度", "${data.avgP} ms", "API 输出")
                if (data.avgQrs > 0) ParamRow("QRS 宽度", "${data.avgQrs} ms", "80-120")
                if (data.prInterval > 0) ParamRow("PR 间期", "${data.prInterval} ms", "120-200")
                if (data.avgQt > 0) ParamRow("QT 间期", "${data.avgQt} ms", "随心率变化")
                if (data.avgQtc > 0) ParamRow("QTc", "${data.avgQtc} ms", "健康参考")
                ParamRow("房性早搏", "${data.pacCount} 次", "API 输出")
                ParamRow("室性早搏", "${data.pvcCount} 次", "API 输出")
                ParamRow("采样率", "${data.sampleRate} Hz", "原始数据")
                Spacer(Modifier.height(6.dp))
                Text(
                    "最低/最高心率由完整单导联波形的有效 R-R 间期本地估算，不是 HeartVoice API 直接返回；无法可靠估算时不会显示猜测值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val sq = data.signalQuality
        val sqColor = if (sq >= 0.7) Color(0xFF388E3C)
        else if (sq >= 0.4) Color(0xFFF57C00) else MaterialTheme.colorScheme.error
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("信号质量", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${(sq * 100).toInt()}%" + when {
                        sq >= 0.7 -> "（良好，仍仅供健康参考）"
                        sq >= 0.4 -> "（一般，结果仅供参考）"
                        else -> "（较差，建议重新测量）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = sqColor,
                )
            }
        }

        Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), enabled = !exporting) {
            Text(if (exporting) "导出中..." else "导出 PDF 报告")
        }
        pdfLocation?.let { location ->
            Text("已保存到：$location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = onOpenPdf, modifier = Modifier.fillMaxWidth()) { Text("打开 PDF") }
        }
        pdfError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("删除此记录")
        }

        Text(
            "本应用展示项目已解析的 HeartVoice 基础版单导联字段；不直接展示未经处理的原始 API JSON。" +
                "结果仅供健康参考，不作为诊断依据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun ParamRow(label: String, value: String, normal: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        Text(
            normal,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
