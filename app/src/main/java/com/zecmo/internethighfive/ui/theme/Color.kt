package com.zecmo.internethighfive.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Defaults for the app-wide background gradient: a lighter, more purple-leaning navy
// fading to a darker slate gray. Tweak live via the gradient debug screen
// (long-press the Profile icon on the Lobby top bar) or edit the defaults here.
val DefaultGradientNavy = Color(0xFF2A2A66)
val DefaultGradientGray = Color(0xFF0E1013)

// Mutable so the debug screen can preview changes in real time across the whole app.
object GradientSettings {
    var navy by mutableStateOf(DefaultGradientNavy)
    var gray by mutableStateOf(DefaultGradientGray)

    fun reset() {
        navy = DefaultGradientNavy
        gray = DefaultGradientGray
    }
}

/** Diagonal top-left -> bottom-right gradient, read live from [GradientSettings]. */
@Composable
fun appBackgroundBrush(): Brush {
    val navy = GradientSettings.navy
    val gray = GradientSettings.gray
    return Brush.linearGradient(
        colors = listOf(navy, gray),
        start = Offset.Zero,
        end = Offset.Infinite
    )
}
