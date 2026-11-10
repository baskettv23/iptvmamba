package com.iptvmamba.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Design values of the player. Same spacing and sizes everywhere so everything looks calm and even. */
object PT {
    // Colors (base colors Bg/Surf/Accent1/Accent2/Muted come from Ui.kt)
    val T2 = Color(0xFFB8BDCC)          // zweite Textfarbe
    val Live = Color(0xFFFF3B5C)
    val Gold = DS.Gold
    val Ink = Color(0xFF0B0C12)         // dunkle Schrift auf weißem Fokus
    val InkSoft = Color(0xFF3C4256)
    val PanelBg = Color(0xF2111320)     // Menüflächen
    val PanelBorder = DS.Overlay1
    val BtnBg = Color(0x24FFFFFF)       // runde Knöpfe im Ruhezustand
    val Track = DS.Overlay3
    val Buffered = Color(0x6BFFFFFF)
    val ScrimTop = Color(0xCC000000)
    val ScrimBottom = Color(0xE6000000)

    // Margins (overscan-safe)
    val MarginX = 56.dp
    val MarginY = 32.dp

    // Buttons
    val BtnSize = 52.dp
    val BtnPrimary = 64.dp
    val BtnGap = 16.dp

    // Menus
    val PanelRadius = 24.dp
    val RowHeight = 52.dp
    val RowGap = 4.dp
}

/** Equal-width digits so clock times do not "jitter". */
val TNUM = TextStyle(fontFeatureSettings = "tnum")
