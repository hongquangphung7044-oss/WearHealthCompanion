package com.wearhealth.companion.mobile.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wearhealth.companion.mobile.ui.diagnosisSummary
import com.wearhealth.companion.mobile.ui.diagnosisToText
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A successful export points to a shareable content URI, never an app-private file path. */
data class PdfExportResult(
    val displayName: String,
    val locationLabel: String,
    val uri: Uri,
)

/** Generates the single-lead ECG reference report with Android's built-in PdfDocument. */
object PdfExporter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val DOWNLOAD_SUBDIRECTORY = "WearHealthCompanion"

    fun displayName(timestamp: Long): String {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
        return "ECG_$time.pdf"
    }

    /** API 29+: publish directly into shared Download/WearHealthCompanion via MediaStore. */
    fun exportToDownloads(context: Context, transfer: EcgMeasurementTransfer): PdfExportResult {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android 8–9 请先选择 PDF 保存位置"
        }
        val name = displayName(transfer.timestamp)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIRECTORY"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("系统无法在 Download 中创建 PDF")
        try {
            resolver.openOutputStream(uri, "w")?.use { writePdf(it, transfer) }
                ?: error("系统无法打开 PDF 输出流")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) { "PDF 已写入但无法发布到 Download" }
            return PdfExportResult(name, "$relativePath/$name", uri)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** API 26–28 compatibility: write to the URI selected by ACTION_CREATE_DOCUMENT/SAF. */
    fun exportToUri(
        context: Context,
        transfer: EcgMeasurementTransfer,
        uri: Uri,
    ): PdfExportResult {
        val name = displayName(transfer.timestamp)
        context.contentResolver.openOutputStream(uri, "w")?.use { writePdf(it, transfer) }
            ?: error("系统无法打开所选保存位置")
        return PdfExportResult(name, "所选文件位置/$name", uri)
    }

    private fun writePdf(output: OutputStream, transfer: EcgMeasurementTransfer) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            drawReport(page.canvas, transfer)
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun drawReport(canvas: Canvas, transfer: EcgMeasurementTransfer) {
        var y = 42f
        val marginX = 40f
        val contentWidth = PAGE_WIDTH - marginX * 2
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val normalPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            isAntiAlias = true
        }
        val smallPaint = Paint().apply {
            color = Color.GRAY
            textSize = 9.5f
            isAntiAlias = true
        }

        canvas.drawText("单导联心电图参考报告", marginX, y, titlePaint)
        val measuredAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(transfer.timestamp))
        y += 22
        canvas.drawText("测量时间：$measuredAt", marginX, y, normalPaint)
        y += 18
        val duration = if (transfer.sampleRate > 0) {
            transfer.rawEcgData.size.toDouble() / transfer.sampleRate
        } else 0.0
        canvas.drawText(
            "原始数据：${transfer.rawEcgData.size} 点 / ${transfer.sampleRate} Hz / ${"%.1f".format(duration)} 秒",
            marginX,
            y,
            normalPaint,
        )
        y += 24

        titlePaint.textSize = 15f
        canvas.drawText("分析结果", marginX, y, titlePaint)
        y += 16
        canvas.drawText(diagnosisToText(transfer.diagnosis), marginX, y, normalPaint)
        y += 15
        y = drawWrappedText(canvas, diagnosisSummary(transfer.diagnosis), marginX, y, contentWidth, normalPaint)
        if (transfer.possibleDiagnoses.isNotEmpty()) {
            y = drawWrappedText(
                canvas,
                "可能诊断（API 提示）：${diagnosisToText(transfer.possibleDiagnoses)}",
                marginX,
                y,
                contentWidth,
                normalPaint,
            )
        }
        y += 5

        canvas.drawText("参数详情", marginX, y, titlePaint)
        y += 15
        drawTableHeader(canvas, marginX, y, contentWidth, smallPaint)
        y += 14
        for ((label, value, normal) in buildParamRows(transfer)) {
            drawParamRow(canvas, label, value, normal, marginX, y, contentWidth, smallPaint)
            y += 14
        }
        y += 5

        canvas.drawText("完整 30 秒波形概览", marginX, y, titlePaint)
        y += 13
        y = drawWrappedText(
            canvas,
            "单导联；纵轴按标准 10 mm/mV 增益；为在一页内呈现完整 30 秒，横轴时间已压缩（非标准 25 mm/s 纸速），可用手机端交互图按标准比例查看细节。",
            marginX,
            y,
            contentWidth,
            smallPaint,
        )
        val chartHeight = 205f
        if (transfer.rawEcgData.isNotEmpty()) {
            drawEcgChart(canvas, transfer.rawEcgData, marginX, y, contentWidth, chartHeight, transfer.sampleRate)
        } else {
            canvas.drawText("无波形数据", marginX, y + chartHeight / 2, smallPaint)
        }
        y += chartHeight + 12

        val disclaimer = "本报告显示的是可观察节律、趋势和相对形态的单导联概览，不是临床 12 导联心电图，" +
            "不用于疾病诊断、治疗或紧急判断；如有不适或异常，请咨询专业医生。"
        drawWrappedText(canvas, disclaimer, marginX, y, contentWidth, Paint(smallPaint).apply {
            color = Color.GRAY
            textSize = 8.5f
        })
    }

    private fun buildParamRows(t: EcgMeasurementTransfer): List<Triple<String, String, String>> = buildList {
        add(Triple("API 异常标志", if (t.isAbnormal) "异常" else "未标记异常", "API 输出"))
        add(Triple("平均心率", "${t.avgHeartRate} bpm", "60-100"))
        add(Triple("最低心率", if (t.minHeartRate > 0) "${t.minHeartRate} bpm" else "暂无可靠估算", "本地趋势"))
        add(Triple("最高心率", if (t.maxHeartRate > 0) "${t.maxHeartRate} bpm" else "暂无可靠估算", "本地趋势"))
        if (t.avgP > 0) add(Triple("平均 P 波宽度", "${t.avgP} ms", "API 输出"))
        if (t.avgQrs > 0) add(Triple("QRS 宽度", "${t.avgQrs} ms", "80-120"))
        if (t.prInterval > 0) add(Triple("PR 间期", "${t.prInterval} ms", "120-200"))
        if (t.avgQt > 0) add(Triple("QT 间期", "${t.avgQt} ms", "随心率变化"))
        if (t.avgQtc > 0) add(Triple("QTc", "${t.avgQtc} ms", "健康参考"))
        add(Triple("房性/室性早搏", "${t.pacCount} / ${t.pvcCount} 次", "API 输出"))
        add(Triple("信号质量", "${(t.signalQuality * 100).toInt()}%", "仅供参考"))
    }

    private fun drawTableHeader(canvas: Canvas, x: Float, y: Float, width: Float, paint: Paint) {
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        canvas.drawLine(x, y - 3, x + width, y - 3, line)
        paint.isFakeBoldText = true
        canvas.drawText("项目", x, y + 7, paint)
        canvas.drawText("数值", x + width / 3, y + 7, paint)
        canvas.drawText("说明", x + width * 2 / 3, y + 7, paint)
        canvas.drawLine(x, y + 10, x + width, y + 10, line)
        paint.isFakeBoldText = false
    }

    private fun drawParamRow(
        canvas: Canvas,
        label: String,
        value: String,
        normal: String,
        x: Float,
        y: Float,
        width: Float,
        paint: Paint,
    ) {
        canvas.drawText(label, x, y + 7, paint)
        canvas.drawText(value, x + width / 3, y + 7, paint)
        canvas.drawText(normal, x + width * 2 / 3, y + 7, paint)
    }

    private fun drawEcgChart(
        canvas: Canvas,
        samples: List<Int>,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        sampleRate: Int,
    ) {
        val chart = android.graphics.RectF(x, y, x + width, y + height)
        canvas.drawRect(chart, Paint().apply { color = Color.rgb(0xFF, 0xF5, 0xF5) })
        if (samples.size < 2) return

        val midY = y + height / 2f

        // 固定电压比例：标准 10 mm/mV。PDF 用户单位 1 unit = 25.4/72 mm。
        val unitsPerMm = 25.4f / 72f
        val yScale = (10f * unitsPerMm) / 1000f // 数据为 mV×1000，1 mV → 10mm

        val fine = Paint().apply { color = Color.rgb(0xFF, 0xCD, 0xD2); strokeWidth = 0.4f }
        val major = Paint().apply { color = Color.rgb(0xEF, 0x9A, 0x9A); strokeWidth = 0.8f }
        val baselinePaint = Paint().apply { color = Color.rgb(0xE5, 0x73, 0x73); strokeWidth = 0.8f }

        // 时间轴：为在一页内呈现完整 30 秒，横轴已压缩（非标准 25 mm/s）。
        val durationSeconds = if (sampleRate > 0) samples.size.toFloat() / sampleRate.toFloat() else 0f
        val xPerSecond = if (durationSeconds > 0f) width / durationSeconds else width
        val xMinorStep = xPerSecond * 0.5f   // 0.5s
        val xMajorStep = xPerSecond           // 1s

        // X 网格（时间）：每 1s 一条粗线，中间 0.5s 一条细线
        var sec = 0
        while (sec * xMajorStep <= width + 0.5f) {
            val mx = x + sec * xMajorStep
            canvas.drawLine(mx, y, mx, y + height, major)
            val halfStep = sec * xMajorStep + xMinorStep
            if (halfStep < width) {
                val fx = x + halfStep
                canvas.drawLine(fx, y, fx, y + height, fine)
            }
            sec++
        }

        // Y 网格（电压）：以基线为基准，每 1mm(0.1mV) 一条细线，每 5mm(0.5mV) 一条粗线
        val yMinorStep = unitsPerMm
        val yMajorStep = 5f * unitsPerMm
        var k = 0
        while (true) {
            val up = midY - k * yMinorStep
            val down = midY + k * yMinorStep
            val paint = if (k % 5 == 0) major else fine
            var drew = false
            if (up >= y) { canvas.drawLine(x, up, x + width, up, paint); drew = true }
            if (k > 0 && down <= y + height) { canvas.drawLine(x, down, x + width, down, paint); drew = true }
            if (!drew && k > 0) break
            k++
        }

        // 基线
        canvas.drawLine(x, midY, x + width, midY, baselinePaint)

        // 去基线后按固定电压比例绘制波形（超出图表范围则裁剪，避免夸张拉伸）
        val mean = samples.average()
        val path = Path()
        val stepX = width / (samples.size - 1).toFloat()
        samples.forEachIndexed { index, sample ->
            val px = x + index * stepX
            val py = (midY - (sample - mean).toFloat() * yScale).coerceIn(y, y + height)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, Paint().apply {
            color = Color.rgb(0x0D, 0x47, 0xA1)
            strokeWidth = 0.8f
            isAntiAlias = true
            style = Paint.Style.STROKE
        })
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
    ): Float {
        var currentY = y
        val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.1f
        var start = 0
        while (start < text.length) {
            var end = text.length
            while (end > start && paint.measureText(text, start, end) > maxWidth) end--
            if (end == start) end++
            canvas.drawText(text, start, end, x, currentY, paint)
            currentY += lineHeight
            start = end
        }
        return currentY
    }
}
