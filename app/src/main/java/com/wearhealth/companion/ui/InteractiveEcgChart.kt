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
 * - 双指缩放：放大/缩小波形
 * - 显示当前缩放比例和位置
 *
 * 适用于结果页和历史详情页（完整波形交互查看）
 *
 * @param samples ECG 数据（mV×1000 整数）
 * @param modifier 布局修饰符
 * @param color 波形颜色
 * @param interactive true=可拖动缩放，false=静态显示
 */
@Composable
fun InteractiveEcgChart(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4CAF50),
    interactive: Boolean = true,
) {
    // 缩放比例（1.0 = 默认显示全部）
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
                            // 缩放（限制 1x ~ 10x）
                            scale = (scale * zoom).coerceIn(1f, 10f)
                            // 拖动（只在放大时有效）
                            if (scale > 1f) {
                                // pan.x 是像素偏移，归一化到 0~1
                                val panNormalized = pan.x / size.width
                                offsetX = (offsetX - panNormalized).coerceIn(0f, 1f - 1f / scale)
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

        // 计算可见窗口
        // scale=1 时显示全部；scale>1 时只显示 1/scale 的数据
        val visibleCount = (samples.size / scale).toInt().coerceAtLeast(10)
        val maxStart = (samples.size - visibleCount).coerceAtLeast(0)
        val startIdx = (offsetX * maxStart).toInt()
        val endIdx = (startIdx + visibleCount).coerceAtMost(samples.size)
        val visibleSamples = samples.subList(startIdx, endIdx)

        // 去基线
        val mean = visibleSamples.average()
        val centered = visibleSamples.map { (it - mean).toFloat() }
        // 自适应缩放
        val maxVal = centered.maxOrNull() ?: 1f
        val minVal = centered.minOrNull() ?: -1f
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val yScale = (h * 0.75f) / range

        // 绘制网格（专业心电图风格）
        val gridColor = Color(0xFF1E3A2E)
        val gridStep = w / 10  // 10 条竖线
        var gridX = 0f
        while (gridX < w) {
            drawLine(gridColor, Offset(gridX, 0f), Offset(gridX, h), 0.5f)
            gridX += gridStep
        }
        var gridY = 0f
        while (gridY < h) {
            drawLine(gridColor, Offset(0f, gridY), Offset(w, gridY), 0.5f)
            gridY += h / 6
        }

        // 基线
        drawLine(Color(0xFF334155), Offset(0f, midY), Offset(w, midY), 1f)

        // 绘制 ECG 波形
        val path = Path()
        val stepX = if (visibleSamples.size > 1) w / (visibleSamples.size - 1) else w
        for (i in centered.indices) {
            val x = i * stepX
            val y = midY - (centered[i] * yScale)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
    }
}
