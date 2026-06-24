package com.zecmo.internethighfive.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Darkest navy pulled from the hi5_logo icon outline, fading to a dark slate gray.
val AppGradientNavy = Color(0xFF112040)
val AppGradientGray = Color(0xFF1C1F24)

val AppBackgroundBrush = Brush.linearGradient(
    colors = listOf(AppGradientNavy, AppGradientGray),
    start = Offset.Zero,
    end = Offset.Infinite
)