package com.zecmo.internethighfive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zecmo.internethighfive.ui.theme.GradientSettings
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradientDebugScreen(onNavigateBack: () -> Unit) {
    // Read/write through GradientSettings directly so every other screen's
    // appBackgroundBrush() recomposes live as these sliders move.
    var navy by remember { mutableStateOf(GradientSettings.navy) }
    var gray by remember { mutableStateOf(GradientSettings.gray) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Gradient Tuner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            ColorTuner(
                label = "Top-left (navy)",
                color = navy,
                onColorChange = {
                    navy = it
                    GradientSettings.navy = it
                }
            )
            ColorTuner(
                label = "Bottom-right (gray)",
                color = gray,
                onColorChange = {
                    gray = it
                    GradientSettings.gray = it
                }
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    GradientSettings.reset()
                    navy = GradientSettings.navy
                    gray = GradientSettings.gray
                }) { Text("Reset to default") }

                OutlinedButton(onClick = {
                    val hex = "navy=#%02X%02X%02X gray=#%02X%02X%02X".format(
                        (navy.red * 255).toInt(), (navy.green * 255).toInt(), (navy.blue * 255).toInt(),
                        (gray.red * 255).toInt(), (gray.green * 255).toInt(), (gray.blue * 255).toInt()
                    )
                    android.util.Log.i("GradientDebug", hex)
                }) { Text("Log hex values") }
            }
        }
    }
}

@Composable
private fun ColorTuner(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
            )
            Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        ChannelSlider("R", color.red) { onColorChange(color.copy(red = it)) }
        ChannelSlider("G", color.green) { onColorChange(color.copy(green = it)) }
        ChannelSlider("B", color.blue) { onColorChange(color.copy(blue = it)) }
    }
}

@Composable
private fun ChannelSlider(channel: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(channel, color = Color.White, modifier = Modifier.width(16.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f)
        )
        Text(
            (value * 255).toInt().toString(),
            color = Color.White,
            modifier = Modifier.width(36.dp)
        )
    }
}
