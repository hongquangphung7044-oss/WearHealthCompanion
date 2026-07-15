package com.wearhealth.companion.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import kotlin.math.floor

/**
 * 可交互的 ECG 波形图
 *
 * 支持：
 * - 单指左右拖动：查看不同时间段
 * - 双指缩放：放大/缩小波形（1x ~ 10x）
 * - 初始 1x 时全部数据铺满绘图区（打开即见全貌），放大后看细节
 * - 固定电压比例 ±2mV（不再自适应拉伸）
 * - 坐标轴标注：底部时间（秒），左侧电压（mV）
 * - 去基线用中位数（抗前段电极建立期漂移）
 *
 * 缩略图模式（interactive=false）保持自适应填充，无坐标轴。
 *
 * @param samples ECG 数据（mV×1000 整数）
 * @param modifier 布局修饰符
 * @param color 波形颜色
 * @param interactive true=可拖动缩放，false=静态缩略图（自适应填充）
 * @param sampleRate 采样率 Hz（默认 500；降采样数据自动按 30 秒推算时长）
 */
@Composable
fun InteractiveEcgChart(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4CAF50),
    interactive: Boolean = true,
    sampleRate: Int = 500,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
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
            val gridColor = Color(0xFF1E3A2E)
            var gridX = 0f
            while (gridX < w) { drawLine(gridColor, Offset(gridX, 0f), Offset(gridX, h), 0.5f); gridX += w / 10 }
            var gridY = 0f
            while (gridY < h) { drawLine(gridColor, Offset(0f, gridY), Offset(w, gridY), 0.5f); gridY += h / 6 }
            drawLine(Color(0xFF334155), Offset(0f, midY), Offset(w, midY), 1f)
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
        val leftPad = 28f
        val bottomPad = 14f
        val topPad = 4f
        val rightPad = 4f
        val plotLeft = leftPad
        val plotTop = topPad
        val plotRight = w - rightPad
        val plotBottom = h - bottomPad
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val midY = plotTop + plotH / 2f

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

        // Y 轴：固定 ±2mV 电压窗口（数据是 mV×1000 整数）
        val yScale = plotH / (4f * 1000f)

        // 时间刻度：根据可见时长选择合适的间隔
        val visibleSec = if (effectiveRate > 0) (lastIdx - firstIdx + 1) / effectiveRate else 0f
        val timeStep = chooseTimeStep(visibleSec)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val axisPaint = Paint().apply {
                color = android.graphics.Color.rgb(0xB0, 0xBE, 0xC5); textSize = 8f; isAntiAlias = true
            }
            val finePaint = Paint().apply {
                color = android.graphics.Color.rgb(0x1E, 0x3A, 0x2E); strokeWidth = 0.5f; isAntiAlias = true
            }
            val majorPaint = Paint().apply {
                color = android.graphics.Color.rgb(0x2E, 0x5A, 0x4E); strokeWidth = 0.8f; isAntiAlias = true
            }
            val baselinePaint = Paint().apply {
                color = android.graphics.Color.rgb(0x33, 0x41, 0x55); strokeWidth = 1f; isAntiAlias = true
            }
            val borderPaint = Paint().apply {
                color = android.graphics.Color.rgb(0x33, 0x41, 0x55)
                strokeWidth = 1f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            val fm = axisPaint.fontMetrics
            val textVertCenter = -(fm.ascent + fm.descent) / 2f

            // ===== Y 网格 + 电压标注（-2 ~ +2 mV，每 1mV 一条） =====
            var mV = -2f
            while (mV <= 2f) {
                val y = midY - mV * 1000f * yScale
                if (y in plotTop..plotBottom) {
                    val p = if (mV == 0f) majorPaint else finePaint
                    nc.drawLine(plotLeft, y, plotRight, y, p)
                    val label = if (mV == 0f) "0" else "${mV.toInt()}"
                    nc.drawText(label, 3f, y + textVertCenter, axisPaint)
                }
                mV += 1f
            }
            // mV 单位
            nc.drawText("mV", 3f, plotTop - fm.ascent + 1f, axisPaint)

            // 基线（0mV 线加粗）
            nc.drawLine(plotLeft, midY, plotRight, midY, baselinePaint)

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
                            val label = formatTimeLabel(t)
                            val tw = axisPaint.measureText(label)
                            val textX = (x - tw / 2f).coerceIn(plotLeft, plotRight - tw)
                            nc.drawText(label, textX, plotBottom + 10f, axisPaint)
                        }
                    }
                }
                t += timeStep
            }

            // 绘图区边框
            nc.drawRect(plotLeft, plotTop, plotRight, plotBottom, borderPaint)
        }

        // ===== ECG 波形 =====
        val path = Path()
        for (i in visibleSamples.indices) {
            val sampleIdx = firstIdx + i
            val x = plotLeft + sampleIdx * stepX - scrollPx
            val y = (midY - (visibleSamples[i] - median) * yScale).coerceIn(plotTop, plotBottom)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    }
}

/** 根据可见时长选择合适的时间刻度间隔（目标约 4~6 条刻度线） */
private fun chooseTimeStep(visibleSec: Float): Float {
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

/** 格式化时间标注：<1s 显示毫秒，整秒显示整数，否则保留一位小数 */
private fun formatTimeLabel(sec: Float): String {
    return when {
        sec < 1f -> "${(sec * 1000).toInt()}"
        sec == sec.toInt().toFloat() -> "${sec.toInt()}"
        else -> "%.1f".format(sec)
    }
}
