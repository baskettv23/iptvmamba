package com.iptvmamba.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.net.URLEncoder

@Composable
fun TelegramListDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("IPTV M3U Liste") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(0.9f).widthIn(max = 760.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Bg).border(1.dp, Surf2, RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Listen in Telegram suchen", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Telegram bietet von außen keine offizielle Gruppensuche. Die Suche öffnet öffentliche Telegram-Verzeichnisse im Browser. Gefundene M3U-Links kopierst du dort und fügst sie hier wieder ein.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp
                )
                Field(query, { query = it }, "Suchbegriff")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusButton("Gruppen durchsuchen", Modifier.focusRequester(first), primary = true) {
                        openUrl(ctx, "https://lyzem.com/search?q=" + enc(query) + "&type=groups")
                    }
                    FocusButton("Kanäle durchsuchen") {
                        openUrl(ctx, "https://lyzem.com/search?q=" + enc(query) + "&type=channels")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusButton("Web-Suche (site:t.me)") {
                        openUrl(ctx, "https://www.google.com/search?q=" + enc("site:t.me " + query))
                    }
                    FocusButton("Telegram öffnen") { openUrl(ctx, "https://t.me/") }
                }
                Text(
                    "Tipp: In den Treffern nach „m3u“, „playlist“ oder „xtream“ suchen. Nur Quellen verwenden, für die du eine Berechtigung hast.",
                    color = Muted, fontSize = 13.sp, lineHeight = 18.sp
                )
                FocusButton("Schließen", primary = true) { onClose() }
            }
        }
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Keine App zum Öffnen gefunden", Toast.LENGTH_LONG).show()
    }
}
