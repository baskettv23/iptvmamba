package com.iptvmamba.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Neon "M" mark with a play spark, drawn in code so it stays crisp and epic at any size.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val w = size.width; val h = size.height
        val neon = Brush.linearGradient(
            listOf(Color(0xFF00F0FF), Color(0xFF3D7BFF), Color(0xFFFF2BD6)),
            start = Offset(w * 0.12f, h * 0.2f), end = Offset(w * 0.88f, h * 0.85f)
        )
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(Color(0x3320E0FF), Color.Transparent),
                center = Offset(w * 0.45f, h * 0.5f), radius = w * 0.55f
            ),
            cornerRadius = CornerRadius(w * 0.28f)
        )
        val m = Path().apply {
            moveTo(w * 0.12f, h * 0.78f); lineTo(w * 0.12f, h * 0.24f)
            lineTo(w * 0.30f, h * 0.24f); lineTo(w * 0.46f, h * 0.52f)
            lineTo(w * 0.62f, h * 0.24f); lineTo(w * 0.80f, h * 0.24f)
            lineTo(w * 0.80f, h * 0.78f); lineTo(w * 0.64f, h * 0.78f)
            lineTo(w * 0.64f, h * 0.46f); lineTo(w * 0.46f, h * 0.74f)
            lineTo(w * 0.28f, h * 0.46f); lineTo(w * 0.28f, h * 0.78f)
            close()
        }
        drawPath(m, brush = neon)
        drawPath(m, color = Color.White.copy(alpha = 0.35f), style = Stroke(width = w * 0.018f, join = StrokeJoin.Round))
        val play = Path().apply {
            moveTo(w * 0.69f, h * 0.50f); lineTo(w * 0.69f, h * 0.80f); lineTo(w * 0.93f, h * 0.65f); close()
        }
        drawPath(play, color = Color.White.copy(alpha = 0.95f))
        drawPath(play, color = Color(0xFF00F0FF).copy(alpha = 0.7f), style = Stroke(width = w * 0.012f, join = StrokeJoin.Round))
    }
}
