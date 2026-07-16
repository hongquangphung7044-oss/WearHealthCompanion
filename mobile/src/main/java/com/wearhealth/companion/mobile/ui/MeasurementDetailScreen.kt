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
import androidx.compose.material3.TopAppBarDefaults
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
import com.wearhealth.companion.shared.JsonCleaner
import org.json.JSONObject
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
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

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
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
                    sampleRate = data.sampleRate,
                )
                Text(
                    "单导联波形；纵轴随可见数据自适应（mV），横轴为时间（秒）；可拖动查看、双指缩放看细节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
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

        // DeepSeek 第二套分析报告（仅 DS 测量存在）
        if (data.analysisMethod.startsWith("ds_") && data.aiReport.isNotBlank()) {
            AiReportCard(aiReportJson = data.aiReport)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
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

/**
 * DeepSeek AI 解读卡片：解析 DS 返回的 JSON 报告，分节展示。
 *
 * DS 作为独立的第二套测量方法，报告包含综合解读/心率分析/节律/间期/异常/建议/免责声明。
 */
@Composable
private fun AiReportCard(aiReportJson: String) {
    val report = remember(aiReportJson) {
        runCatching { parseAiReport(aiReportJson) }.getOrNull()
    } ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("AI 解读（DeepSeek）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            report.summary?.let {
                SectionLabel("综合解读")
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            report.heartRate?.let {
                SectionLabel("心率分析")
                Text(it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            report.rhythm?.let {
                SectionLabel("节律分析")
                Text(it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            report.intervals?.let {
                SectionLabel("间期评估（本地估测，DS 判断）")
                Text(it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            if (report.abnormal.isNotEmpty()) {
                SectionLabel("异常提示")
                Text(
                    report.abnormal.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (report.advice.isNotEmpty()) {
                SectionLabel("健康建议")
                report.advice.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
            }
            report.disclaimer?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

private data class ParsedAiReport(
    val summary: String?,
    val heartRate: String?,
    val rhythm: String?,
    val intervals: String?,
    val abnormal: List<String>,
    val advice: List<String>,
    val disclaimer: String?,
)

private fun parseAiReport(json: String): ParsedAiReport {
    val cleaned = JsonCleaner.extractJsonObject(json)
    val obj = JSONObject(cleaned)

    // 扁平 JSON 格式（DS 输出嵌套对象易产生语法错误，已改为顶层字段）
    val heartRate = buildString {
        obj.opt("平均心率")?.let { append("平均心率：$it bpm\n") }
        obj.optString("心率范围").takeIf { it.isNotEmpty() }?.let { append("心率范围：$it\n") }
        obj.optString("心率稳定性").takeIf { it.isNotEmpty() }?.let { append("心率稳定性：$it\n") }
        obj.optString("心率置信度").takeIf { it.isNotEmpty() }?.let { append("置信度：$it\n") }
        obj.opt("SDNN_ms")?.let { append("SDNN：$it ms\n") }
        obj.opt("RMSSD_ms")?.let { append("RMSSD：$it ms\n") }
        obj.opt("pNN50_pct")?.let { append("pNN50：$it %\n") }
        obj.optString("HRV解读").takeIf { it.isNotEmpty() }?.let { append("HRV 解读：$it\n") }
        obj.optString("HRV置信度").takeIf { it.isNotEmpty() }?.let { append("HRV 置信度：$it\n") }
    }.trimEnd().takeIf { it.isNotEmpty() }

    val rhythm = buildString {
        obj.optString("节律判断").takeIf { it.isNotEmpty() }?.let { append("节律判断：$it\n") }
        obj.optString("RR间期规律性").takeIf { it.isNotEmpty() }?.let { append("RR 规律性：$it\n") }
        obj.optJSONArray("早搏提示")?.let { arr ->
            if (arr.length() > 0) {
                append("早搏提示：")
                for (i in 0 until arr.length()) {
                    append(arr.optString(i))
                    if (i < arr.length() - 1) append("；")
                }
                append("\n")
            }
        }
        obj.optString("节律置信度").takeIf { it.isNotEmpty() }?.let { append("置信度：$it\n") }
    }.trimEnd().takeIf { it.isNotEmpty() }

    val intervals = buildString {
        obj.opt("PR间期_ms")?.let { append("PR 间期：$it ms") }
        obj.optString("PR判断").takeIf { it.isNotEmpty() }?.let { append("（$it）") }
        obj.optString("PR置信度").takeIf { it.isNotEmpty() }?.let { append(" [置信度：$it]") }
        append("\n")
        obj.opt("QRS宽度_ms")?.let { append("QRS 宽度：$it ms") }
        obj.optString("QRS判断").takeIf { it.isNotEmpty() }?.let { append("（$it）") }
        obj.optString("QRS置信度").takeIf { it.isNotEmpty() }?.let { append(" [置信度：$it]") }
        append("\n")
        obj.opt("QTc_ms")?.let { append("QTc：$it ms") }
        obj.optString("QTc判断").takeIf { it.isNotEmpty() }?.let { append("（$it）") }
        obj.optString("QTc置信度").takeIf { it.isNotEmpty() }?.let { append(" [置信度：$it]") }
        append("\n")
        obj.optString("间期数据来源").takeIf { it.isNotEmpty() }?.let { append(it) }
    }.trimEnd().takeIf { it.isNotEmpty() }

    val abnormal = obj.optJSONArray("异常提示")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    } ?: emptyList()

    val advice = obj.optJSONArray("健康建议")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
    } ?: emptyList()

    return ParsedAiReport(
        summary = obj.optString("综合解读").takeIf { it.isNotEmpty() },
        heartRate = heartRate,
        rhythm = rhythm,
        intervals = intervals,
        abnormal = abnormal,
        advice = advice,
        disclaimer = obj.optString("免责声明").takeIf { it.isNotEmpty() },
    )
}
