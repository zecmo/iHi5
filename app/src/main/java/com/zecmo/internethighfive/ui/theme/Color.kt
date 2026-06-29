package com.zecmo.internethighfive.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Defaults for the app-wide background gradient: indigo (top-left) fading to
// near-black slate (bottom-right). Tweak live via the gradient debug screen
// (long-press the Profile icon on the Lobby top bar) or edit the defaults here.
val DefaultGradientNavy = Color(0xFF2A2A8E)
val DefaultGradientGray = Color(0xFF0E1012)

private const val PREFS_NAME = "gradient_settings"
private const val KEY_NAVY = "navy_argb"
private const val KEY_GRAY = "gray_argb"

// Mutable so the debug screen can preview changes in real time across the whole app.
// Persists to SharedPreferences via save()/init() so a chosen palette survives restarts.
object GradientSettings {
    var navy by mutableStateOf(DefaultGradientNavy)
    var gray by mutableStateOf(DefaultGradientGray)

    /** Call once at app startup (before the first composition) to restore saved colors, if any. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_NAVY)) navy = Color(prefs.getInt(KEY_NAVY, navy.toArgb()))
        if (prefs.contains(KEY_GRAY)) gray = Color(prefs.getInt(KEY_GRAY, gray.toArgb()))
    }

    /** Persists the current navy/gray as the user's saved palette. */
    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVY, navy.toArgb())
            .putInt(KEY_GRAY, gray.toArgb())
            .apply()
    }

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
