package com.wearhealth.companion.ui

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
 * 可交互的 ECG 波形图
 *
 * 支持：
 * - 单指左右拖动：查看不同时间段
 * - 双指缩放：放大/缩小波形（0.2x ~ 10x）
 * - 固定电压比例（10 mm/mV 基准），不再自适应拉伸
 *
 * 适用于结果页和历史详情页（完整波形交互查看）
 *
 * @param samples ECG 数据（mV×1000 整数）
 * @param modifier 布局修饰符
 * @param color 波形颜色
 * @param interactive true=可拖动缩放，false=静态缩略图（自适应填充）
 * @param sampleRate 采样率 Hz（默认 500）
 */
@Composable
fun InteractiveEcgChart(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4CAF50),
    interactive: Boolean = true,
    sampleRate: Int = 500,
) {
    // 缩放比例（1.0 = 默认显示约 4 秒；>1 放大看细节；<0.2 缩小看全貌）
    var scale by remember { mutableFloatStateOf(1f) }
    // 水平偏移（0f = 最左，1f = 最右）
    var offsetX by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (interactive) {
                    Modifier.pointerInput(samples) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 缩放（允许 0.2x ~ 10x：缩小看全貌，放大看细节）
                            scale = (scale * zoom).coerceIn(0.2f, 10f)
                            // 拖动
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
            // 缩略图模式：保持原有的自适应填充方式
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

        // ===== 交互模式：固定时间/电压比例 =====
        // 基准：1x 缩放时每秒 50px（手表屏约 200px 宽 → 显示约 4 秒）
        val basePxPerSecond = 50f
        // 固定电压比例：1mV = 30px（数据是 mV×1000 整数）
        val pxPerMillivolt = 30f

        val pxPerSecond = basePxPerSecond * scale
        val stepX = pxPerSecond / sampleRate.toFloat()

        // 计算可见范围
        val totalContentWidth = samples.size * stepX
        val maxScroll = (totalContentWidth - w).coerceAtLeast(0f)
        val scrollPx = offsetX * maxScroll
        val firstIdx = (scrollPx / stepX).toInt().coerceIn(0, samples.size - 1)
        val lastIdx = ((scrollPx + w) / stepX).toInt().coerceIn(firstIdx, samples.size - 1)
        val visibleSamples = samples.subList(firstIdx, lastIdx + 1)

        // 去基线（用可见段均值，消除低频漂移）
        val mean = visibleSamples.average()
        val yScale = pxPerMillivolt / 1000f

        // ===== 网格：按 ECG 标准刻度（1mm 细格 / 5mm 大格）=====
        // X: 1mm = 0.04s → pxPerSecond/25；5mm = 0.2s → pxPerSecond/5
        val fineStepX = pxPerSecond / 25f
        val majorStepX = pxPerSecond / 5f
        // Y: 1mm = 0.1mV → pxPerMillivolt/10；5mm = 0.5mV → pxPerMillivolt/2
        val fineStepY = pxPerMillivolt / 10f
        val majorStepY = pxPerMillivolt / 2f

        val fineColor = Color(0xFF1E3A2E)
        val majorColor = Color(0xFF2E5A4E)

        // 细格
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
        // 大格（每 5 条细格加粗）
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

        // 基线
        drawLine(Color(0xFF334155), Offset(0f, midY), Offset(w, midY), 1f)

        // 绘制 ECG 波形
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
