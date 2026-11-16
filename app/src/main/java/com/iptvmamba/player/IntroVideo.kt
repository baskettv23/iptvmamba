package com.iptvmamba.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Elite neon intro: animated mark, glow rings and title. Skips on any key/tap, auto-finishes.
 */
@Composable
fun IntroVideo(onDone: () -> Unit) {
    val current by rememberUpdatedState(onDone)
    val focus = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    val finish = { if (!done) { done = true; current() } }
    val reveal = remember { Animatable(0f) }
    val pulse = rememberInfiniteTransition(label = "intro")
    val glow by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    val sweep by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "s")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF060A1A), Color(0xFF02030A), Color(0xFF000000)), radius = 1400f))
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown) { finish(); true } else false }
            .clickable { finish() }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.42f)
            val r = size.minDimension * (0.18f + 0.03f * sweep)
            drawCircle(Color(0xFF00F0FF).copy(alpha = 0.08f * glow), r * 2.4f, c)
            drawCircle(Color(0xFFFF2BD6).copy(alpha = 0.06f * glow), r * 1.8f, c)
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color(0x0000F0FF), Color(0xFF00F0FF), Color(0xFFFF2BD6), Color(0x0000F0FF)), center = c),
                radius = r, center = c, style = Stroke(width = 6f), alpha = 0.8f
            )
            drawCircle(Color(0xFF3D7BFF).copy(alpha = 0.12f), r * 0.85f, c)
        }
        Column(
            Modifier.align(Alignment.Center).alpha(reveal.value),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BrandMark(Modifier.size(170.dp))
            Text("iptvmamba", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "ELITE STREAMING",
                color = Color(0xFF00F0FF),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
        }
        Text(
            "Press any key to skip",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp)
        )
        Spacer(Modifier.height(0.dp))
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        delay(2600)
        finish()
    }
}
