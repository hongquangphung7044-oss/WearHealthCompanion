package com.wearhealth.companion.mobile.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.wearhealth.companion.shared.EcgMeasurementTransfer
import com.wearhealth.companion.mobile.ui.diagnosisSummary
import com.wearhealth.companion.mobile.ui.diagnosisToText
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PDF 报告导出器
 *
 * 使用 Android 内置 [PdfDocument] API 生成 A4 尺寸的心电图报告，不依赖第三方库。
 *
 * 报告内容：
 * 1. 标题"心电图报告"
 * 2. 测量时间
 * 3. 诊断结果（中文化 + 通俗解读）
 * 4. 参数表格（心率、QRS、PR、QT、QTc、早搏等）
 * 5. 完整 ECG 波形图（带专业网格背景，去基线 + 自适应缩放）
 * 6. 底部免责声明
 */
object PdfExporter {

    private const val TAG = "PdfExporter"

    // A4 尺寸（PostScript point, 1pt = 1/72 inch）
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    /**
     * 导出 PDF 到应用私有目录
     *
     * @param context 应用上下文
     * @param transfer 完整测量数据（含原始波形）
     * @return 生成的 PDF 文件绝对路径
     */
    fun export(context: Context, transfer: EcgMeasurementTransfer): String {
        val dir = File(context.filesDir, "ecg_reports").apply { mkdirs() }
        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(transfer.timestamp))
        val file = File(dir, "ECG_$timeStr.pdf")
        export(context, transfer, file.absolutePath)
        return file.absolutePath
    }

    /**
     * 导出 PDF 到指定路径
     *
     * @param outputPath 输出文件绝对路径
     */
    fun export(context: Context, transfer: EcgMeasurementTransfer, outputPath: String) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            drawReport(page.canvas, transfer)
            document.finishPage(page)

            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                document.writeTo(fos)
            }
            Log.i(TAG, "PDF 已导出: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "导出 PDF 失败: ${e.message}", e)
            throw e
        } finally {
            document.close()
        }
    }

    /** 绘制完整报告 */
    private fun drawReport(canvas: Canvas, transfer: EcgMeasurementTransfer) {
        var y = 50f
        val marginX = 40f
        val contentWidth = PAGE_WIDTH - marginX * 2

        // ===== 标题 =====
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val title = "心电图报告"
        canvas.drawText(title, marginX, y, titlePaint)
        y += 16

        // ===== 测量时间 =====
        val normalPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 12f
            isAntiAlias = true
        }
        val smallPaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            isAntiAlias = true
        }
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(transfer.timestamp))
        canvas.drawText("测量时间：$timeStr", marginX, y + 16, normalPaint)
        y += 36

        // ===== 诊断结果 =====
        canvas.drawText("诊断结果", marginX, y, titlePaint.apply { textSize = 16f })
        y += 18
        titlePaint.textSize = 24f // 复用前恢复
        canvas.drawText(diagnosisToText(transfer.diagnosis), marginX, y, normalPaint)
        y += 18
        // 通俗解读（自动换行）
        val summary = diagnosisSummary(transfer.diagnosis)
        y = drawWrappedText(canvas, summary, marginX, y, contentWidth, normalPaint)
        y += 8

        // ===== 参数表格 =====
        canvas.drawText("参数详情", marginX, y, titlePaint.apply { textSize = 16f })
        y += 18
        titlePaint.textSize = 24f
        val params = buildParamRows(transfer)
        // 表头
        drawTableHeader(canvas, marginX, y, contentWidth, smallPaint)
        y += 16
        for ((label, value, normal) in params) {
            drawParamRow(canvas, label, value, normal, marginX, y, contentWidth, smallPaint)
            y += 16
        }
        y += 8

        // ===== ECG 波形图 =====
        canvas.drawText("心电图波形", marginX, y, titlePaint.apply { textSize = 16f })
        y += 18
        titlePaint.textSize = 24f
        val chartHeight = 240f
        if (transfer.rawEcgData.isNotEmpty()) {
            drawEcgChart(canvas, transfer.rawEcgData, marginX, y, contentWidth, chartHeight)
        } else {
            canvas.drawText("无波形数据", marginX, y + chartHeight / 2, smallPaint)
        }
        y += chartHeight + 16

        // ===== 免责声明 =====
        val disclaimerPaint = Paint(smallPaint).apply {
            color = Color.GRAY
            textSize = 9f
            isItalic = true
        }
        val disclaimer = "本报告由 AI 自动分析生成，仅供健康参考，不作为诊断依据。" +
                "如有异常请咨询专业医生。"
        drawWrappedText(canvas, disclaimer, marginX, y, contentWidth, disclaimerPaint)
    }

    /** 构建参数表格行数据 */
    private fun buildParamRows(t: EcgMeasurementTransfer): List<Triple<String, String, String>> {
        val rows = mutableListOf<Triple<String, String, String>>()
        rows.add(Triple("平均心率", "${t.avgHeartRate} bpm", "60-100"))
        if (t.minHeartRate > 0 && t.maxHeartRate > 0) {
            rows.add(Triple("心率范围", "${t.minHeartRate} ~ ${t.maxHeartRate} bpm", "60-100"))
        }
        if (t.avgQrs > 0) rows.add(Triple("QRS 宽度", "${t.avgQrs} ms", "80-120"))
        if (t.prInterval > 0) rows.add(Triple("PR 间期", "${t.prInterval} ms", "120-200"))
        if (t.avgQt > 0) rows.add(Triple("QT 间期", "${t.avgQt} ms", "随心率变化"))
        if (t.avgQtc > 0) rows.add(Triple("QTc", "${t.avgQtc} ms", "男<450 / 女<460"))
        rows.add(Triple("房性早搏", "${t.pacCount} 次", "0"))
        rows.add(Triple("室性早搏", "${t.pvcCount} 次", "0"))
        rows.add(Triple("信号质量", "${(t.signalQuality * 100).toInt()}%", "≥70%"))
        rows.add(Triple("采样率", "${t.sampleRate} Hz", "-"))
        return rows
    }

    /** 表头 */
    private fun drawTableHeader(
        canvas: Canvas, x: Float, y: Float, width: Float, paint: Paint,
    ) {
        val linePaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        canvas.drawLine(x, y - 4, x + width, y - 4, linePaint)
        canvas.drawText("项目", x, y + 8, paint.apply { isFakeBoldText = true })
        canvas.drawText("数值", x + width / 3, y + 8, paint)
        canvas.drawText("正常范围", x + width * 2 / 3, y + 8, paint)
        canvas.drawLine(x, y + 12, x + width, y + 12, linePaint)
        paint.isFakeBoldText = false
    }

    /** 参数行 */
    private fun drawParamRow(
        canvas: Canvas, label: String, value: String, normal: String,
        x: Float, y: Float, width: Float, paint: Paint,
    ) {
        canvas.drawText(label, x, y + 8, paint)
        canvas.drawText(value, x + width / 3, y + 8, paint)
        canvas.drawText(normal, x + width * 2 / 3, y + 8, paint)
    }

    /**
     * 绘制 ECG 波形图（带专业网格背景）
     *
     * - 去基线 + 自适应幅度缩放
     * - 用 [Path] 绘制波形
     */
    private fun drawEcgChart(
        canvas: Canvas,
        samples: List<Int>,
        x: Float, y: Float,
        width: Float, height: Float,
    ) {
        val chart = android.graphics.RectF(x, y, x + width, y + height)

        // 背景（浅粉，模拟心电图纸）
        val bgPaint = Paint().apply { color = Color.rgb(0xFF, 0xF5, 0xF5) }
        canvas.drawRect(chart, bgPaint)

        // 细网格
        val finePaint = Paint().apply { color = Color.rgb(0xFF, 0xCD, 0xD2); strokeWidth = 0.4f }
        val fineStepX = width / 50f
        var gx = x
        while (gx <= x + width) {
            canvas.drawLine(gx, y, gx, y + height, finePaint)
            gx += fineStepX
        }
        val fineStepY = height / 30f
        var gy = y
        while (gy <= y + height) {
            canvas.drawLine(x, gy, x + width, gy, finePaint)
            gy += fineStepY
        }
        // 大网格
        val majorPaint = Paint().apply { color = Color.rgb(0xEF, 0x9A, 0x9A); strokeWidth = 0.8f }
        val majorStepX = width / 10f
        gx = x
        while (gx <= x + width) {
            canvas.drawLine(gx, y, gx, y + height, majorPaint)
            gx += majorStepX
        }
        val majorStepY = height / 6f
        gy = y
        while (gy <= y + height) {
            canvas.drawLine(x, gy, x + width, gy, majorPaint)
            gy += majorStepY
        }

        // 基线
        val midY = y + height / 2f
        val baselinePaint = Paint().apply { color = Color.rgb(0xE5, 0x73, 0x73); strokeWidth = 0.5f }
        canvas.drawLine(x, midY, x + width, midY, baselinePaint)

        if (samples.size < 2) return

        // 去基线
        val mean = samples.average()
        val centered = samples.map { (it - mean).toFloat() }
        val maxVal = centered.maxOrNull() ?: 1f
        val minVal = centered.minOrNull() ?: -1f
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val yScale = (height * 0.75f) / range

        // 绘制波形
        val wavePaint = Paint().apply {
            color = Color.rgb(0x0D, 0x47, 0xA1)
            strokeWidth = 1f
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        val path = Path()
        val stepX = width / (centered.size - 1).toFloat()
        for (i in centered.indices) {
            val px = x + i * stepX
            val py = midY - (centered[i] * yScale)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, wavePaint)
    }

    /**
     * 自动换行绘制文本，返回下一行起始 y 坐标
     */
    private fun drawWrappedText(
        canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint,
    ): Float {
        var currentY = y
        val lineHeight = paint.fontMetrics.let { it.descent - it.ascent } * 1.1f
        var start = 0
        while (start < text.length) {
            var end = text.length
            while (end > start && paint.measureText(text, start, end) > maxWidth) {
                end--
            }
            if (end == start) end = start + 1 // 至少画一个字符
            canvas.drawText(text, start, end, x, currentY, paint)
            currentY += lineHeight
            start = end
        }
        return currentY
    }
}
