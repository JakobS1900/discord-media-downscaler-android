package com.jakob.dmd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jakob.dmd.ui.theme.DmdAccent
import com.jakob.dmd.ui.theme.DmdBg3
import com.jakob.dmd.ui.theme.DmdGreen
import com.jakob.dmd.ui.theme.DmdGreen2
import com.jakob.dmd.ui.theme.DmdPurple
import kotlin.math.PI
import kotlin.math.sin

/**
 * Compose port of main.py:90 AnimatedProgressBar.
 *  - Filled gradient (blurple → purple, or green when done)
 *  - Sliding shimmer stripe while in progress
 *  - Idle trough pulse when at 0
 */
@Composable
fun AnimatedProgressBar(
    progress: Float,        // 0.0 .. 1.0
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "progressbar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    val isDone = progress >= 0.999f
    val barStart = if (isDone) DmdGreen else DmdAccent
    val barEnd = if (isDone) DmdGreen2 else DmdPurple

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val w = size.width
        val h = size.height
        val fillW = (w * progress.coerceIn(0f, 1f))

        // Trough
        val troughIntensity = if (progress <= 0f) 0.18f * (sin(pulse * 2 * PI).toFloat() + 1) / 2f else 0f
        val troughColor = lerpColor(DmdBg3, Color.White, troughIntensity)
        drawRect(color = troughColor, topLeft = Offset.Zero, size = Size(w, h))

        if (fillW < 2f) return@Canvas

        // Filled gradient
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(barStart, barEnd),
                startX = 0f, endX = fillW,
            ),
            topLeft = Offset.Zero,
            size = Size(fillW, h),
        )

        // Shimmer stripe (only while in progress, not done)
        if (!isDone) {
            val shimmerCenter = (phase * (fillW + 80f)) - 40f
            val shimmerWidth = 64f
            val shimmerLeft = (shimmerCenter - shimmerWidth / 2).coerceAtLeast(0f)
            val shimmerRight = (shimmerCenter + shimmerWidth / 2).coerceAtMost(fillW)
            if (shimmerRight > shimmerLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0f),
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0f),
                        ),
                        startX = shimmerLeft, endX = shimmerRight,
                    ),
                    topLeft = Offset(shimmerLeft, 0f),
                    size = Size(shimmerRight - shimmerLeft, h),
                )
            }
        } else {
            // Done glow (gentle pulse over the whole bar)
            val glow = 0.20f * (sin(pulse * 2 * PI).toFloat() + 1) / 2f
            drawRect(
                color = Color.White.copy(alpha = glow),
                topLeft = Offset.Zero,
                size = Size(fillW, h),
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt,
    )
}
