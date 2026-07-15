package com.wearhealth.companion.mobile.ui

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 手机端 ECG 波形图组件
 *
 * 固定时间/电压比例（交互模式）：
 * - 1x 缩放时每秒 100px（手机屏约 360px 宽 → 显示约 3.6 秒，约 3-4 个心跳）
 * - 固定电压比例 50px/mV（接近标准 10mm/mV 视觉比例）
 * - 网格按 ECG 标准刻度（1mm 细格 / 5mm 大格）
 *
 * 缩略图模式（interactive=false）保持自适应填充。
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
                            scale = (scale * zoom).coerceIn(0.2f, 10f)
                            val panNormalized = pan.x / size.width
                            val maxOffset = 1f - 1f / scale.coerceAtLeast(1f)
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
        val midY = h / 2f

        if (!interactive) {
            // 缩略图模式：自适应填充
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

        // ===== 交互模式：固定时间/电压比例 =====
        val basePxPerSecond = 100f
        val pxPerMillivolt = 50f

        val pxPerSecond = basePxPerSecond * scale
        val stepX = pxPerSecond / sampleRate.toFloat()

        val totalContentWidth = samples.size * stepX
        val maxScroll = (totalContentWidth - w).coerceAtLeast(0f)
        val scrollPx = offsetX * maxScroll
        val firstIdx = (scrollPx / stepX).toInt().coerceIn(0, samples.size - 1)
        val lastIdx = ((scrollPx + w) / stepX).toInt().coerceIn(firstIdx, samples.size - 1)
        val visibleSamples = samples.subList(firstIdx, lastIdx + 1)

        val mean = visibleSamples.average()
        val yScale = pxPerMillivolt / 1000f

        // ===== 网格：按 ECG 标准刻度 =====
        val fineStepX = pxPerSecond / 25f
        val majorStepX = pxPerSecond / 5f
        val fineStepY = pxPerMillivolt / 10f
        val majorStepY = pxPerMillivolt / 2f

        val fineColor = Color(0xFFFFCDD2)
        val majorColor = Color(0xFFEF9A9A)

        var gx = scrollPx % fineStepX
        while (gx < w) {
            if (gx >= 0) drawLine(fineColor, Offset(gx, 0f), Offset(gx, h), 0.5f)
            gx += fineStepX
        }
        var gy = midY % fineStepY
        while (gy < h) {
            if (gy >= 0) drawLine(fineColor, Offset(0f, gy), Offset(w, gy), 0.5f)
            gy += fineStepY
        }
        var mx = scrollPx % majorStepX
        while (mx < w) {
            if (mx >= 0) drawLine(majorColor, Offset(mx, 0f), Offset(mx, h), 1f)
            mx += majorStepX
        }
        var my = midY % majorStepY
        while (my < h) {
            if (my >= 0) drawLine(majorColor, Offset(0f, my), Offset(w, my), 1f)
            my += majorStepY
        }

        drawLine(Color(0xFFE57373), Offset(0f, midY), Offset(w, midY), 1f)

        val path = Path()
        for (i in visibleSamples.indices) {
            val sampleIdx = firstIdx + i
            val x = sampleIdx * stepX - scrollPx
            val y = midY - ((visibleSamples[i] - mean).toFloat() * yScale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    }
}
