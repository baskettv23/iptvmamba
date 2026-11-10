package com.iptvmamba.player

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Donation link of the developer (PayPal). The app stays free – this is only a voluntary thank-you. */
object Donate {
    const val URL = "https://www.paypal.com/ncp/payment/EUSU4WAGRJNXY"
    /** Short form for display (without https://). */
    val SHORT: String get() = URL.removePrefix("https://www.")
}

/** Settings > About: small donation area with text, QR code and link (no button: TVs often have no browser). */
@Composable
internal fun DonateSection(@Suppress("UNUSED_PARAMETER") ctx: Context) {
    SectionTitle("Spende")
    Hint("Hallo, ich bin der nigerianische Prinz und brauche dringend deine Hilfe! Spaß beiseite: Die App kostet dich nichts, mich aber ein paar Nächte Schlaf und sehr viel Kaffee. Wenn du mir ein paar Euro schicken möchtest, tanzt mein Kontostand vor Freude. Kein Zwang: Die App bleibt kostenlos, werbefrei und GPL-lizenziert.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QrImage(Donate.URL, 160.dp)
        Text("Dieser QR-Code ist der PayPal-Link für meine Spende. Mit der Handy-Kamera scannen, dann öffnet sich PayPal.", color = Muted, fontSize = 15.sp)
    }
    Text("PayPal: " + Donate.SHORT, color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, raw = true)
}
