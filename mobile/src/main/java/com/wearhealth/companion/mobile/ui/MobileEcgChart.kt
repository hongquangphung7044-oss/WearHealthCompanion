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
 * 相比手表端的 InteractiveEcgChart，提供更大的显示尺寸与更专业的网格背景：
 * - 经典 ECG 心电图纸风格：浅粉背景 + 粉色网格（大格 5mm，细格 1mm 比例）
 * - 单指拖动平移、双指缩放（1x~10x）
 * - 自适应去基线 + 幅度缩放
 *
 * @param samples ECG 数据（mV×1000 整数）
 * @param modifier 布局修饰符
 * @param color 波形颜色
 * @param interactive true=可拖动缩放，false=静态显示
 * @param chartHeight 图表高度，详情页用 240dp，列表缩略图用 100dp
 */
@Composable
fun MobileEcgChart(
    samples: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF0D47A1),
    interactive: Boolean = true,
    chartHeight: androidx.compose.ui.unit.Dp = 240.dp,
) {
    // 缩放比例（1.0 = 默认显示全部）
    var scale by remember { mutableFloatStateOf(1f) }
    // 水平偏移（0f = 最左，1f = 最右）
    var offsetX by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .height(chartHeight)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFF5F5)) // 浅粉背景，模拟心电图纸
            .then(
                if (interactive) {
                    Modifier.pointerInput(samples) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 缩放（限制 1x ~ 10x）
                            scale = (scale * zoom).coerceIn(1f, 10f)
                            // 拖动（只在放大时有效）
                            if (scale > 1f) {
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

        // ===== 专业 ECG 网格背景 =====
        // 细格（1mm）
        val fineColor = Color(0xFFFFCDD2)
        val fineStep = w / 50  // 50 条细竖线
        var fineX = 0f
        while (fineX < w) {
            drawLine(fineColor, Offset(fineX, 0f), Offset(fineX, h), 0.5f)
            fineX += fineStep
        }
        val fineStepY = h / 30f
        var fineY = 0f
        while (fineY < h) {
            drawLine(fineColor, Offset(0f, fineY), Offset(w, fineY), 0.5f)
            fineY += fineStepY
        }
        // 大格（5mm）
        val majorColor = Color(0xFFEF9A9A)
        val majorStep = w / 10 // 10 条大竖线
        var majorX = 0f
        while (majorX < w) {
            drawLine(majorColor, Offset(majorX, 0f), Offset(majorX, h), 1f)
            majorX += majorStep
        }
        val majorStepY = h / 6f
        var majorY = 0f
        while (majorY < h) {
            drawLine(majorColor, Offset(0f, majorY), Offset(w, majorY), 1f)
            majorY += majorStepY
        }

        // 基线
        drawLine(Color(0xFFE57373), Offset(0f, midY), Offset(w, midY), 1f)

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
