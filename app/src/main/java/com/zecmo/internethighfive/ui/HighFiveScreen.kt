package com.zecmo.internethighfive.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighFiveScreen(
    partnerId: String,
    onNavigateBack: () -> Unit,
    viewModel: HighFiveViewModel = viewModel()
) {
    val context = LocalContext.current
    val highFiveState by viewModel.highFiveState.collectAsState()
    val highFiveSession by viewModel.highFiveSession.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val error by viewModel.error.collectAsState()
    val partnerStats by viewModel.partnerStats.collectAsState()
    val sessionMessage = highFiveSession?.message?.takeIf { it.isNotBlank() }

    // Sensor + force tracking
    var currentForce by remember { mutableStateOf(0f) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // ToneGenerator removed — audio tones stacked badly at accelerometer game rate

    fun vibrate(force: Float) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ms = when {
                    force > 15f -> 200L; force > 10f -> 140L; force > 5f -> 80L; else -> 40L
                }
                val amp = when {
                    force > 15f -> 255; force > 10f -> 200; force > 5f -> 150; else -> 100
                }
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60L)
            }
            // No audio tone — the accelerometer fires at game rate so tones stack up rapidly
        } catch (e: Exception) {
            Log.e("HighFiveScreen", "vibrate failed", e)
        }
    }

    val sensorListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val (x, y, z) = event.values
                    val force = (sqrt(x * x + y * y + z * z) - 9.8f).coerceAtLeast(0f)
                    currentForce = force
                    if (force > 3f) vibrate(force)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(Unit) {
        sensorManager.registerListener(
            sensorListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME
        )
        onDispose {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    // Session init — wait for currentUser to load before creating/joining session
    var sessionStarted by remember { mutableStateOf(false) }
    LaunchedEffect(currentUser) {
        if (currentUser != null && !sessionStarted) {
            sessionStarted = true
            when {
                partnerId.startsWith("open:") -> {
                    val message = partnerId.removePrefix("open:")
                    viewModel.onEnterHighFiveScreen()
                    viewModel.openSession(message = message)
                }
                partnerId.startsWith("invite:") -> {
                    // format: "invite:<friendId>:<friendName>:<message>"
                    val parts = partnerId.removePrefix("invite:").split(":", limit = 3)
                    val friendId = parts.getOrElse(0) { "" }
                    val friendName = parts.getOrElse(1) { "" }
                    val message = parts.getOrElse(2) { "" }
                    viewModel.onEnterHighFiveScreen()
                    viewModel.openSession(message = message, invitePartnerId = friendId, inviteReceiverName = friendName)
                }
                else -> {
                    // Joining someone else's session — don't raise our own hand
                    viewModel.connectToUser(partnerId)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onExitHighFiveScreen() }
    }

    // Countdown trigger: derived purely from the session row — both devices read the
    // same `partnerPresent` and so can never disagree about whether to start.
    val bothConnected by viewModel.partnerPresent.collectAsState()
    var countdown by remember { mutableStateOf<Int?>(null) }

    // Each device runs its own local 3-2-1 the moment it sees a partner. Exact sync
    // isn't needed here — actual scoring uses server timestamps, not the countdown.
    LaunchedEffect(bothConnected) {
        if (!bothConnected) return@LaunchedEffect
        if (highFiveState is HighFiveState.Success || highFiveState is HighFiveState.Error) return@LaunchedEffect
        countdown = 3; delay(1000L)
        countdown = 2; delay(1000L)
        countdown = 1; delay(1000L)
        countdown = null
        viewModel.readyToTap()
    }

    LaunchedEffect(highFiveState) {
        if (highFiveState is HighFiveState.Success) {
            viewModel.loadPartnerStats()
        }
    }

    val partnerName = highFiveSession?.let {
        if (currentUser?.id == it.initiatorId) it.partnerUsername else it.initiatorUsername
    }?.takeIf { it.isNotEmpty() } ?: "Partner"

    // For a direct invite, we know the target's name up front (it rides in the nav arg)
    // even before they join — so we can name them instead of showing "Finding Partner…".
    val inviteTargetName = remember(partnerId) {
        if (partnerId.startsWith("invite:")) {
            partnerId.removePrefix("invite:").split(":", limit = 3).getOrNull(1)?.takeIf { it.isNotBlank() }
        } else null
    }
    // Before the partner connects, prefer the known invite target name.
    val waitingName = if (bothConnected) partnerName else (inviteTargetName ?: partnerName)

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            bothConnected -> "High Five with $partnerName"
                            inviteTargetName != null -> "Raised hand to $inviteTargetName"
                            else -> "Finding $partnerName…"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            // Error banner — always visible so we can diagnose issues
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                )
            }

            when {
                highFiveState is HighFiveState.Success -> {
                    SuccessContent(
                        quality = (highFiveState as HighFiveState.Success).quality,
                        message = sessionMessage,
                        partnerName = partnerName,
                        stats = partnerStats
                    )
                }
                highFiveState is HighFiveState.Error -> {
                    ErrorContent(
                        message = (highFiveState as HighFiveState.Error).message,
                        onRetry = onNavigateBack
                    )
                }
                countdown != null -> {
                    CountdownContent(count = countdown!!)
                }
                !bothConnected -> {
                    WaitingContent(partnerName = waitingName, message = sessionMessage)
                }
                highFiveState is HighFiveState.Waiting -> {
                    WaitingTapContent(partnerName = partnerName)
                }
                else -> {
                    // Idle — tap area active
                    TapContent(
                        currentForce = currentForce,
                        onTap = {
                            viewModel.initiateHighFive()
                        }
                    )
                }
            }
        }
    }
}

// ── Sub-screens ────────────────────────────────────────────────────────────────

@Composable
private fun WaitingContent(partnerName: String, message: String? = null) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (message != null) {
            Text(
                "\"$message\"",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
        Icon(
            Icons.Default.BackHand,
            contentDescription = null,
            modifier = Modifier.size(120.dp).alpha(alpha),
            tint = MaterialTheme.colorScheme.primary
        )
        Text("Hand is Up! ✋", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CountdownContent(count: Int) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale"
    )
    val color = when (count) {
        3 -> MaterialTheme.colorScheme.tertiary
        2 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("GET READY", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = count.toString(),
            fontSize = 120.sp,
            fontWeight = FontWeight.Black,
            color = color,
            modifier = Modifier.scale(scale)
        )
    }
}

@Composable
private fun TapContent(currentForce: Float, onTap: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = (1f + currentForce / 25f).coerceIn(1f, 1.4f),
        animationSpec = tween(50),
        label = "scale"
    )
    val tint by animateColorAsState(
        targetValue = when {
            currentForce > 10f -> MaterialTheme.colorScheme.error
            currentForce > 5f  -> MaterialTheme.colorScheme.tertiary
            else               -> MaterialTheme.colorScheme.primary
        },
        label = "tint"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.pressed }) onTap()
                }
            }
        }
    ) {
        Text("TAP!", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Icon(
            Icons.Default.BackHand,
            contentDescription = "Tap to high five",
            modifier = Modifier.size(220.dp).scale(scale),
            tint = tint
        )
        if (currentForce > 2f) {
            Text(
                "Force: ${"%.1f".format(currentForce)}G",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WaitingTapContent(partnerName: String) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "alpha"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            Icons.Default.BackHand,
            contentDescription = null,
            modifier = Modifier.size(160.dp).alpha(alpha),
            tint = MaterialTheme.colorScheme.secondary
        )
        Text("Waiting for $partnerName…", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
        Text("You tapped! Hold on…", style = MaterialTheme.typography.bodyLarge, color = Color.White)
    }
}

@Composable
private fun SuccessContent(
    quality: Float,
    message: String? = null,
    partnerName: String = "Partner",
    stats: PartnerStats? = null
) {
    val (label, color) = when {
        quality >= 1.0f -> "PERFECT! 🌟" to Color(0xFFFFD700)
        quality >= 0.8f -> "GREAT! ⭐"   to Color(0xFF4CAF50)
        quality >= 0.6f -> "GOOD! 👍"    to Color(0xFF2196F3)
        quality >= 0.4f -> "OK 👋"       to Color(0xFFFF9800)
        else            -> "MEH 🤷"      to Color(0xFF9E9E9E)
    }
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).scale(scale)
    ) {
        Text("HIGH FIVE!", fontSize = 48.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        LinearProgressIndicator(
            progress = { quality },
            modifier = Modifier.fillMaxWidth(0.6f).height(12.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            "${"%.0f".format(quality * 100)}% sync",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message != null) {
            Text(
                "\"$message\"",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(4.dp))
        if (stats == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Text(
                "${stats.flavorEmoji} ${stats.flavorLabel}",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "You & $partnerName have high fived ${stats.totalCount} time${if (stats.totalCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (stats.qualityBreakdown.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("Perfect!" to "🌟", "Great!" to "⭐", "Good" to "👍", "Ok" to "👋", "Meh" to "🤷")
                        .forEach { (key, emoji) ->
                            val count = stats.qualityBreakdown[key] ?: 0
                            if (count > 0) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "$emoji $count",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text("Go back to the lobby to try again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Back to Lobby") }
    }
}
