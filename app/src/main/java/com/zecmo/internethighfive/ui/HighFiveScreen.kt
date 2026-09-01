package com.zecmo.internethighfive.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
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
import com.zecmo.internethighfive.BuildConfig
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
    val lastTimeDiffMs by viewModel.lastTimeDiffMs.collectAsState()
    val sessionMessage = highFiveSession?.message?.takeIf { it.isNotBlank() }

    // SoundPool for result SFX
    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    val sfxIds = remember {
        mapOf(
            "perfect" to soundPool.load(context, com.zecmo.internethighfive.R.raw.sfx_perfect, 1),
            "great"   to soundPool.load(context, com.zecmo.internethighfive.R.raw.sfx_great,   1),
            "good"    to soundPool.load(context, com.zecmo.internethighfive.R.raw.sfx_good,    1),
            "ok"      to soundPool.load(context, com.zecmo.internethighfive.R.raw.sfx_ok,      1),
            "meh"     to soundPool.load(context, com.zecmo.internethighfive.R.raw.sfx_meh,     1)
        )
    }
    DisposableEffect(Unit) { onDispose { soundPool.release() } }

    // Sensor + force tracking
    var currentForce by remember { mutableStateOf(0f) }
    // Hardest hit seen this session — debug readout only, for threshold tuning.
    var peakForce by remember { mutableStateOf(0f) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // ToneGenerator removed — audio tones stacked badly at accelerometer game rate

    fun vibrateOnTap() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(60L, 200))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60L)
            }
        } catch (e: Exception) {
            Log.e("HighFiveScreen", "vibrate failed", e)
        }
    }

    // A slap is two signals that arrive close together, not simultaneously: fingers hit
    // the glass first, the impact impulse peaks a few ms later. Correlate over a window.
    val slapDetector = remember { SlapDetector() }
    slapDetector.onSlap = { if (viewModel.initiateHighFive()) vibrateOnTap() }

    // Accelerometer drives the TapContent force visual AND the impact half of the slap
    // gesture — no vibration here anymore. Haptic fires once, on a confirmed slap.
    val sensorListener = remember {
        object : SensorEventListener {
            // One force excursion = one candidate slap. Hysteresis (threshold up,
            // release down) keeps the chassis ringing from splitting into several.
            private var aboveThreshold = false
            private var firedThisExcursion = false
            private var excursionJerk = 0f
            private var lastUiPublishAt = 0L
            private var prevForce = 0f
            private var prevTsNs = 0L
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val (x, y, z) = event.values
                    val force = (sqrt(x * x + y * y + z * z) - 9.8f).coerceAtLeast(0f)
                    // Detection below reads every sample; the UI state is throttled.
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiPublishAt >= FORCE_UI_PUBLISH_MS) {
                        lastUiPublishAt = now
                        currentForce = force
                    }
                    if (force > peakForce) peakForce = force

                    // Rise rate, from the sensor's own clock — this is what tells a slap
                    // apart from a shake. Peak force alone cannot; the two overlap.
                    var jerk = 0f
                    if (prevTsNs != 0L) {
                        val dtS = (event.timestamp - prevTsNs) / 1_000_000_000f
                        if (dtS > 0f) jerk = (force - prevForce) / dtS
                    }
                    prevForce = force
                    prevTsNs = event.timestamp

                    if (force > SLAP_FORCE_THRESHOLD) {
                        if (!aboveThreshold) {
                            aboveThreshold = true
                            firedThisExcursion = false
                            excursionJerk = 0f
                        }
                        // Tracked across the whole excursion, not just the crossing
                        // sample: the crossing catches the gentlest part of the rise and
                        // reading jerk there rejects even a 114G strike.
                        if (jerk > excursionJerk) excursionJerk = jerk
                        if (!firedThisExcursion && excursionJerk >= SLAP_JERK_THRESHOLD) {
                            firedThisExcursion = true
                            Log.d(
                                "HighFiveScreen",
                                "slap force=%.1f jerk=%.0f".format(force, excursionJerk)
                            )
                            slapDetector.onImpact()
                        }
                    } else if (aboveThreshold && force < SLAP_FORCE_RELEASE) {
                        aboveThreshold = false
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(Unit) {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Never let a sensor rate we can't get take the whole screen down: SENSOR_DELAY_FASTEST
        // (0us) throws SecurityException on API 31+ without HIGH_SAMPLING_RATE_SENSORS, and OEM
        // /GrapheneOS builds vary in what they'll grant. Ask for 200Hz, degrade to GAME instead.
        val registered = try {
            sensorManager.registerListener(sensorListener, accelerometer, SLAP_SENSOR_PERIOD_US)
        } catch (e: SecurityException) {
            Log.w("HighFiveScreen", "High-rate sensor denied, falling back to GAME rate", e)
            sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        if (!registered) Log.w("HighFiveScreen", "Accelerometer unavailable — slap detection degraded")
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
    // The countdown is only a visual hint now — tapping is enabled immediately so a
    // player can go early instead of waiting for it to reach zero.
    LaunchedEffect(bothConnected) {
        if (!bothConnected) return@LaunchedEffect
        if (highFiveState is HighFiveState.Success || highFiveState is HighFiveState.Error) return@LaunchedEffect
        viewModel.readyToTap()
        countdown = 3; delay(750L)
        countdown = 2; delay(750L)
        countdown = 1; delay(750L)
        countdown = null
    }

    LaunchedEffect(highFiveState) {
        if (highFiveState is HighFiveState.Success) {
            viewModel.loadPartnerStats()
            val quality = (highFiveState as HighFiveState.Success).quality
            val key = when {
                quality >= 1.0f -> "perfect"
                quality >= 0.8f -> "great"
                quality >= 0.6f -> "good"
                quality >= 0.4f -> "ok"
                else            -> "meh"
            }
            sfxIds[key]?.let { soundPool.play(it, 1f, 1f, 0, 0, 1f) }
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SuccessContent(
                            quality = (highFiveState as HighFiveState.Success).quality,
                            message = sessionMessage,
                            partnerName = partnerName,
                            stats = partnerStats
                        )
                        DebugReadout(timeDiffMs = lastTimeDiffMs, peakForce = peakForce)
                    }
                }
                highFiveState is HighFiveState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorContent(
                            message = (highFiveState as HighFiveState.Error).message,
                            onRetry = onNavigateBack
                        )
                        DebugReadout(timeDiffMs = lastTimeDiffMs, peakForce = peakForce)
                    }
                }
                !bothConnected -> {
                    WaitingContent(partnerName = waitingName, message = sessionMessage)
                }
                highFiveState is HighFiveState.Waiting -> {
                    WaitingTapContent(partnerName = partnerName)
                }
                else -> {
                    // Idle — tap area active. The countdown (if still running) overlays
                    // as a hint only; it has no pointer input of its own, so taps pass
                    // straight through to TapContent underneath.
                    Box(contentAlignment = Alignment.Center) {
                        TapContent(
                            currentForce = currentForce,
                            onMultiTouch = { slapDetector.onMultiTouch() },
                            onBypassTap = { if (viewModel.initiateHighFive()) vibrateOnTap() }
                        )
                        countdown?.let { CountdownHint(count = it) }
                    }
                }
            }
        }
    }
}

// ── Sub-screens ────────────────────────────────────────────────────────────────

/**
 * Debug-build-only tuning readout, pinned to the bottom of the result screen.
 *
 * `timeDiffMs` is the raw sync gap the quality tier was derived from, and `peakForce`
 * is the hardest hit the accelerometer saw this session — the two numbers needed to
 * recalibrate the scoring windows and [SLAP_FORCE_THRESHOLD] against real play. Emits
 * nothing in release builds.
 */
@Composable
private fun BoxScope.DebugReadout(timeDiffMs: Long?, peakForce: Float) {
    if (!BuildConfig.DEBUG) return
    val tier = timeDiffMs?.let {
        when {
            it < 100 -> "perfect"; it < 300 -> "great"; it < 500 -> "good"
            it < 800 -> "ok"; it <= 3000 -> "meh"; else -> "tooSlow"
        }
    } ?: "—"
    Text(
        text = "debug · diff=${timeDiffMs?.let { "${it}ms" } ?: "—"} · tier=$tier · peak=${"%.1f".format(peakForce)}G · thr=${"%.0f".format(SLAP_FORCE_THRESHOLD)}G",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
    )
}

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
private fun CountdownHint(count: Int) {
    val color = when (count) {
        3 -> MaterialTheme.colorScheme.tertiary
        2 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    // Purely a visual hint overlaid above TapContent — it declares no pointer input,
    // so it never intercepts taps. 3-2-1 is a guide, not a lock.
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "TAP WHENEVER YOU'RE READY",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = count.toString(),
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            color = color.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun TapContent(currentForce: Float, onMultiTouch: () -> Unit, onBypassTap: () -> Unit) {
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
    // pointerInput(Unit) only launches its coroutine once, so it would otherwise close
    // over a stale callback from first composition — rememberUpdatedState keeps it live.
    val latestOnMultiTouch = rememberUpdatedState(onMultiTouch)
    val latestOnBypassTap = rememberUpdatedState(onBypassTap)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                // Rising edge only — a held palm produces a stream of move events and
                // should report contact once, not on every frame.
                var wasMultiTouch = false
                var wasPressed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val isMultiTouch = pressed >= 2
                    // TODO: accessibility setting to allow single-tap fallback (see backlog).
                    if (isMultiTouch && !wasMultiTouch) latestOnMultiTouch.value.invoke()
                    // Emulator only: any single press completes the high five outright.
                    if (emulatorTapBypass && pressed >= 1 && !wasPressed) {
                        latestOnBypassTap.value.invoke()
                    }
                    wasMultiTouch = isMultiTouch
                    wasPressed = pressed >= 1
                }
            }
        }
    ) {
        Text("SLAP IT!", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        if (emulatorTapBypass) {
            Text(
                "debug · emulator: tap anywhere",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.BackHand,
            contentDescription = "Slap the screen with your hand to high five",
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
