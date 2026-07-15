package com.wearhealth.companion.mobile.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 手机端 ECG 波形图组件
 *
 * 交互模式（interactive=true）：
 * - 初始 1x 时全部数据铺满绘图区（打开即见全貌），放大后看细节
 * - Y 轴自适应：根据可见数据范围自动调整电压刻度（跟随数据变化）
 * - 坐标轴标注：底部时间（秒），左侧电压（mV）
 * - 去基线用中位数（抗前段电极建立期漂移）
 *
 * 缩略图模式（interactive=false）保持自适应填充，无坐标轴。
 *
 * @param samples ECG 数据（mV×1000 整数）
 * @param modifier 布局修饰符
 * @param color 波形颜色
 * @param interactive true=可拖动缩放，false=静态缩略图
 * @param chartHeight 图表高度
 * @param sampleRate 采样率 Hz（默认 500）
 */
@Composable
fun MobileEcgChart(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF0D47A1),
    interactive: Boolean = true,
    chartHeight: androidx.compose.ui.unit.Dp = 240.dp,
    sampleRate: Int = 500,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .height(chartHeight)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFF5F5))
            .then(
                if (interactive) {
                    Modifier.pointerInput(samples) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 10f)
                            val panNormalized = pan.x / size.width
                            val maxOffset = 1f - 1f / scale
                            if (maxOffset > 0f) {
                                offsetX = (offsetX - panNormalized).coerceIn(0f, maxOffset)
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        if (samples.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height

        if (!interactive) {
            // 缩略图模式：自适应填充（保持不变）
            val midY = h / 2f
            val mean = samples.average()
            val centered = samples.map { (it - mean).toFloat() }
            val range = ((centered.maxOrNull() ?: 1f) - (centered.minOrNull() ?: -1f)).coerceAtLeast(1f)
            val yScale = (h * 0.75f) / range
            val fineColor = Color(0xFFFFCDD2)
            val majorColor = Color(0xFFEF9A9A)
            var fineX = 0f
            while (fineX < w) { drawLine(fineColor, Offset(fineX, 0f), Offset(fineX, h), 0.5f); fineX += w / 50 }
            var fineY = 0f
            while (fineY < h) { drawLine(fineColor, Offset(0f, fineY), Offset(w, fineY), 0.5f); fineY += h / 30f }
            var majorX = 0f
            while (majorX < w) { drawLine(majorColor, Offset(majorX, 0f), Offset(majorX, h), 1f); majorX += w / 10 }
            var majorY = 0f
            while (majorY < h) { drawLine(majorColor, Offset(0f, majorY), Offset(w, majorY), 1f); majorY += h / 6f }
            drawLine(Color(0xFFE57373), Offset(0f, midY), Offset(w, midY), 1f)
            val path = Path()
            val stepX = if (samples.size > 1) w / (samples.size - 1) else w
            for (i in centered.indices) {
                val x = i * stepX
                val y = midY - (centered[i] * yScale)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
            return@Canvas
        }

        // ===== 交互模式：初始铺满 + 缩放看细节 =====
        // 边距：左侧给 Y 轴标注，底部给 X 轴标注
        val leftPad = 38f
        val bottomPad = 20f
        val topPad = 6f
        val rightPad = 6f
        val plotLeft = leftPad
        val plotTop = topPad
        val plotRight = w - rightPad
        val plotBottom = h - bottomPad
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

        // 时长推断：降采样数据（点数/采样率 < 5 秒）按 30 秒处理
        val durationSec = if (sampleRate > 0 && samples.size.toFloat() / sampleRate > 5f) {
            samples.size.toFloat() / sampleRate
        } else 30f
        val effectiveRate = if (durationSec > 0f) samples.size.toFloat() / durationSec else sampleRate.toFloat()

        // X 轴：scale=1 时全部铺满，scale>1 放大
        val baseStepX = if (samples.size > 1) plotW / (samples.size - 1) else plotW
        val stepX = baseStepX * scale
        val totalContentWidth = (samples.size - 1) * stepX
        val maxScroll = (totalContentWidth - plotW).coerceAtLeast(0f)
        val scrollPx = offsetX * maxScroll

        val firstIdx = (scrollPx / stepX).toInt().coerceIn(0, samples.size - 1)
        val lastIdx = ((scrollPx + plotW) / stepX).toInt().coerceIn(firstIdx, samples.size - 1)
        val visibleSamples = samples.subList(firstIdx, lastIdx + 1)

        // 去基线：中位数（抗前段电极建立期漂移，比均值更稳定）
        val sortedVisible = visibleSamples.sorted()
        val median = if (sortedVisible.isNotEmpty()) sortedVisible[sortedVisible.size / 2].toFloat() else 0f

        // Y 轴：自适应电压窗口（根据可见数据范围自动调整，跟随缩放/拖动变化）
        val centeredMax = (visibleSamples.maxOrNull() ?: 1000).toFloat() - median
        val centeredMin = (visibleSamples.minOrNull() ?: -1000).toFloat() - median
        val dataSpan = (centeredMax - centeredMin).coerceAtLeast(200f) // 至少 0.2mV，防退化
        val pad = dataSpan * 0.15f
        val rawMaxMv = (centeredMax + pad) / 1000f
        val rawMinMv = (centeredMin - pad) / 1000f
        val yStepMv = chooseVoltageStepMobile(rawMaxMv - rawMinMv)
        val yTopMv = ceil(rawMaxMv / yStepMv) * yStepMv
        val yBottomMv = floor(rawMinMv / yStepMv) * yStepMv
        val yRangeMv = (yTopMv - yBottomMv).coerceAtLeast(yStepMv)
        val yScale = plotH / (yRangeMv * 1000f)
        // 0mV 在画布上的 Y 坐标（数据是 mV×1000 整数）
        val zeroY = plotTop + yTopMv * 1000f * yScale

        // 时间刻度：根据可见时长选择合适的间隔
        val visibleSec = if (effectiveRate > 0) (lastIdx - firstIdx + 1) / effectiveRate else 0f
        val timeStep = chooseTimeStepMobile(visibleSec)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val axisPaint = Paint().apply {
                setColor(android.graphics.Color.rgb(0x5C, 0x6B, 0x73)); textSize = 10f; isAntiAlias = true
            }
            val finePaint = Paint().apply {
                setColor(android.graphics.Color.rgb(0xFF, 0xCD, 0xD2)); strokeWidth = 0.5f; isAntiAlias = true
            }
            val majorPaint = Paint().apply {
                setColor(android.graphics.Color.rgb(0xEF, 0x9A, 0x9A)); strokeWidth = 0.8f; isAntiAlias = true
            }
            val baselinePaint = Paint().apply {
                setColor(android.graphics.Color.rgb(0xE5, 0x73, 0x73)); strokeWidth = 1f; isAntiAlias = true
            }
            val borderPaint = Paint().apply {
                setColor(android.graphics.Color.rgb(0xE5, 0x73, 0x73))
                strokeWidth = 1f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            val fm = axisPaint.fontMetrics
            val textVertCenter = -(fm.ascent + fm.descent) / 2f

            // ===== Y 网格 + 电压标注（自适应范围，按 yStepMv 间隔） =====
            val nSteps = ((yTopMv - yBottomMv) / yStepMv).toInt().coerceAtLeast(1)
            for (i in 0..nSteps) {
                val mV = yBottomMv + i * yStepMv
                val y = zeroY - mV * 1000f * yScale
                if (y in plotTop..plotBottom) {
                    val p = if (mV == 0f) majorPaint else finePaint
                    nc.drawLine(plotLeft, y, plotRight, y, p)
                    val label = if (mV == 0f) "0" else formatVoltageLabelMobile(mV)
                    nc.drawText(label, 4f, y + textVertCenter, axisPaint)
                }
            }
            // mV 单位
            nc.drawText("mV", 4f, plotTop - fm.ascent + 2f, axisPaint)

            // 基线（0mV 线加粗）
            nc.drawLine(plotLeft, zeroY, plotRight, zeroY, baselinePaint)

            // ===== X 网格 + 时间标注（秒） =====
            val startSec = if (effectiveRate > 0) firstIdx / effectiveRate else 0f
            val endSec = if (effectiveRate > 0) (lastIdx + 1) / effectiveRate else 0f
            var t = floor(startSec / timeStep).toFloat() * timeStep
            while (t <= endSec + timeStep) {
                if (t >= 0f) {
                    val idx = (t * effectiveRate).toInt()
                    if (idx in 0 until samples.size) {
                        val x = plotLeft + idx * stepX - scrollPx
                        if (x in plotLeft..plotRight) {
                            nc.drawLine(x, plotTop, x, plotBottom, majorPaint)
                            val label = formatTimeLabelMobile(t)
                            val tw = axisPaint.measureText(label)
                            val textX = (x - tw / 2f).coerceIn(plotLeft, plotRight - tw)
                            nc.drawText(label, textX, plotBottom + 14f, axisPaint)
                        }
                    }
                }
                t += timeStep
            }
            // s 单位（右下角）
            nc.drawText("s", plotRight - 10f, plotBottom + 14f, axisPaint)

            // 绘图区边框
            nc.drawRect(plotLeft, plotTop, plotRight, plotBottom, borderPaint)
        }

        // ===== ECG 波形 =====
        val path = Path()
        for (i in visibleSamples.indices) {
            val sampleIdx = firstIdx + i
            val x = plotLeft + sampleIdx * stepX - scrollPx
            val y = (zeroY - (visibleSamples[i] - median) * yScale).coerceIn(plotTop, plotBottom)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    }
}

/** 根据可见时长选择合适的时间刻度间隔（目标约 4~6 条刻度线） */
private fun chooseTimeStepMobile(visibleSec: Float): Float {
    val target = (visibleSec / 5f).coerceAtLeast(0.1f)
    val steps = floatArrayOf(0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f)
    var best = steps[0]
    var bestDiff = abs(target - best)
    for (s in steps) {
        val d = abs(target - s)
        if (d < bestDiff) { best = s; bestDiff = d }
    }
    return best
}

/** 根据电压范围选择合适的刻度间隔（目标约 4~6 条刻度线） */
private fun chooseVoltageStepMobile(rangeMv: Float): Float {
    val target = (rangeMv / 5f).coerceAtLeast(0.1f)
    val steps = floatArrayOf(0.1f, 0.2f, 0.5f, 1f, 2f, 5f, 10f)
    var best = steps[0]
    var bestDiff = abs(target - best)
    for (s in steps) {
        val d = abs(target - s)
        if (d < bestDiff) { best = s; bestDiff = d }
    }
    return best
}

/** 格式化电压标注：整数显示整数，否则保留一位小数 */
private fun formatVoltageLabelMobile(mv: Float): String {
    return if (mv == mv.toInt().toFloat()) "${mv.toInt()}" else "%.1f".format(mv)
}

/** 格式化时间标注：<1s 显示毫秒，整秒显示整数，否则保留一位小数 */
private fun formatTimeLabelMobile(sec: Float): String {
    return when {
        sec < 1f -> "${(sec * 1000).toInt()}"
        sec == sec.toInt().toFloat() -> "${sec.toInt()}"
        else -> "%.1f".format(sec)
    }
}
