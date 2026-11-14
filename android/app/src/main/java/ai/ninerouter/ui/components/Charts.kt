package ai.ninerouter.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Brand
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextPrimary

/**
 * A slim progress bar that eases to its new value. Used for provider usage,
 * quota windows and any other ratio in the analytics screens.
 */
@Composable
fun AnimatedBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    trackColor: Color = AppSurfaceHigh,
    durationMillis: Int = 650
) {
    val target = progress.coerceIn(0f, 1f)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(target, durationMillis) {
        reveal.animateTo(target, tween(durationMillis = durationMillis, easing = FastOutSlowInEasing))
    }
    val animated = reveal.value
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.72f), color)
                    )
                )
        )
    }
}

/**
 * Animated bar chart with a soft line overlay. Values are expected in 0..1.
 * The whole chart grows in once and then animates whenever the data changes.
 */
@Composable
fun AnimatedBarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Brand,
    height: Dp = 108.dp,
    labels: List<String> = emptyList()
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 760, easing = FastOutSlowInEasing))
    }
    val progress = reveal.value
    val peak = values.maxOrNull()?.coerceAtLeast(0.001f) ?: 0.001f
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val slot = size.width / values.size.coerceAtLeast(1)
            val barWidth = (slot * 0.52f).coerceAtLeast(2f)
            val usableHeight = size.height - 6.dp.toPx()
            val points = ArrayList<Offset>(values.size)

            values.forEachIndexed { index, raw ->
                val ratio = (raw / peak).coerceIn(0f, 1f) * progress
                val barHeight = (ratio * usableHeight).coerceAtLeast(if (raw > 0f) 3.dp.toPx() else 0f)
                val left = index * slot + (slot - barWidth) / 2f
                val top = size.height - barHeight
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.35f), color)
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
                points += Offset(left + barWidth / 2f, top)
            }

            if (points.size > 1) {
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (index in 1 until points.size) {
                        val prev = points[index - 1]
                        val current = points[index]
                        val mid = (prev.x + current.x) / 2f
                        cubicTo(mid, prev.y, mid, current.y, current.x, current.y)
                    }
                }
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.55f),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            drawLine(
                color = color.copy(alpha = 0.18f),
                start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 1f
            )
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Circular quota gauge with an eased sweep and a centered percentage. */
@Composable
fun QuotaGauge(
    remainingPercent: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp
) {
    val target = (remainingPercent / 100f).coerceIn(0f, 1f)
    val arc = remember { Animatable(0f) }
    LaunchedEffect(target) {
        arc.animateTo(target, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val sweep = arc.value
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = AppSurfaceHigh,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color)),
                startAngle = -90f,
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${remainingPercent.coerceIn(0f, 100f).toInt()}%",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                color = TextPrimary
            )
            Text("left", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

/**
 * Tiny sparkline used inside quota cards for local request volume. Values are
 * arbitrary non-negative numbers; the chart normalises them automatically.
 */
@Composable
fun MiniSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    val progress = reveal.value
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (values.isEmpty()) return@Canvas
        val peak = values.maxOrNull()?.coerceAtLeast(0.001f) ?: 0.001f
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - (value / peak).coerceIn(0f, 1f) * size.height * progress
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
