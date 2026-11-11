package com.iptvmamba.player

import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Short branded clip on app start. Plays once, skips on any key/tap, and fails open
 * (6 s timeout + error listener) so it can never block the app.
 */
@Composable
fun IntroVideo(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val current by rememberUpdatedState(onDone)
    val focus = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    val finish: () -> Unit = {
        if (!done) {
            done = true
            current()
        }
    }

    DisposableEffect(Unit) { onDispose { runCatching { video?.stopPlayback() } } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown) { finish(); true } else false }
            .clickable { finish() }
    ) {
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setVideoURI(Uri.parse("android.resource://${c.packageName}/${R.raw.intro}"))
                    setOnPreparedListener { mp -> mp.isLooping = false; start() }
                    setOnCompletionListener { finish() }
                    setOnErrorListener { _, _, _ -> finish(); true }
                    video = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Text(
            "Überspringen  ▶",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp)
        )
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(6000)
        finish()
    }
}
