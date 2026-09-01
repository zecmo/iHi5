package com.zecmo.internethighfive.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zecmo.internethighfive.ui.theme.appBackgroundBrush
import kotlin.math.max
import kotlin.math.sqrt

private const val MAX_LOG_LINES = 12

/**
 * Standalone diagnostic harness for the slap gesture — no session, no partner, no
 * network. Exists because "it doesn't slap how I expect" is unanswerable without
 * seeing which half of the gesture is failing.
 *
 * Read it like this:
 *  - `ptr` never reaching 2       -> the touchscreen is collapsing the palm into one
 *    contact. Check `maj`: if that spikes on a palm slap while `ptr` stays 1, contact
 *    size is the signal to detect on instead of pointer count.
 *  - no IMPACT lines              -> the force threshold is higher than a real slap
 *    produces on this device.
 *  - TOUCH and IMPACT but no SLAP -> both halves fire, but further apart than the
 *    correlation window allows; widen it.
 *
 * Runs the real [SlapDetector], so whatever settings work here work on the real screen.
 *
 * Uses pointerInteropFilter rather than Compose's pointer API because only the raw
 * MotionEvent exposes contact area (getTouchMajor/getSize).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SlapTestScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val threshold = remember { mutableStateOf(SLAP_FORCE_THRESHOLD) }
    val release = remember { mutableStateOf(SLAP_FORCE_RELEASE) }
    val window = remember { mutableStateOf(SLAP_WINDOW_MS.toFloat()) }
    // Locked by default: the whole screen is a slap target, and a stray palm landing on
    // a live slider mid-test would silently corrupt the numbers being measured.
    var slidersUnlocked by remember { mutableStateOf(false) }
    var requireTouch by remember { mutableStateOf(true) }
    val refractory = remember { mutableStateOf(SLAP_REFRACTORY_MS.toFloat()) }
    val jerkGate = remember { mutableStateOf(SLAP_JERK_THRESHOLD) }

    var currentForce by remember { mutableStateOf(0f) }
    var peakForce by remember { mutableStateOf(0f) }
    var pointers by remember { mutableStateOf(0) }
    var maxPointers by remember { mutableStateOf(0) }
    var touchMajor by remember { mutableStateOf(0f) }
    var maxTouchMajor by remember { mutableStateOf(0f) }
    var contactSize by remember { mutableStateOf(0f) }
    var maxContactSize by remember { mutableStateOf(0f) }
    var touchCount by remember { mutableStateOf(0) }
    var impactCount by remember { mutableStateOf(0) }
    var slapCount by remember { mutableStateOf(0) }
    var wasMulti by remember { mutableStateOf(false) }
    // Touch->impact gap of the current excursion; the number the window must cover.
    var pendingGap by remember { mutableStateOf<Long?>(null) }
    var lastGap by remember { mutableStateOf<Long?>(null) }
    var maxGap by remember { mutableStateOf(0L) }
    // Rise rate in G/s at threshold crossing — the slap-vs-shake discriminator.
    var lastJerk by remember { mutableStateOf(0f) }
    var maxJerk by remember { mutableStateOf(0f) }
    var rejectedCount by remember { mutableStateOf(0) }
    val log = remember { mutableStateListOf<String>() }

    val startedAt = remember { SystemClock.elapsedRealtime() }
    fun stamp() = "%6.2fs".format((SystemClock.elapsedRealtime() - startedAt) / 1000f)
    fun logLine(line: String) {
        log.add(0, "${stamp()}  $line")
        while (log.size > MAX_LOG_LINES) log.removeAt(log.size - 1)
        // Mirrored to logcat: the on-screen log keeps only MAX_LOG_LINES and is wiped by
        // Reset or leaving the screen, so a whole tuning session is otherwise unrecoverable.
        // Pull with: adb logcat -s SlapTest
        Log.i("SlapTest", "${stamp()}  $line")
    }

    fun buzz(ms: Long, amp: Int) {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (e: Exception) {
            Log.e("SlapTest", "vibrate failed", e)
        }
    }

    val detector = remember { SlapDetector() }
    detector.windowMs = window.value.toLong()
    detector.requireTouch = requireTouch
    detector.refractoryMs = refractory.value.toLong()
    detector.onSlap = {
        slapCount++
        lastGap = pendingGap
        pendingGap?.let { g -> if (g > maxGap) maxGap = g }
        logLine(
            "*** SLAP #$slapCount  gap=${pendingGap ?: 0}ms  " +
            "[thr=%.0f win=%.0f ref=%.0f jerkgate=%.0f touch=%s] jerk=%.0f ***".format(
                threshold.value, window.value, refractory.value, jerkGate.value, requireTouch, lastJerk
            )
        )
        buzz(60L, 200)
    }

    val listener = remember {
        object : SensorEventListener {
            private var aboveThreshold = false
            private var lastUiPublishAt = 0L
            private var excursionPeak = 0f
            private var excursionJerk = 0f
            private var firedThisExcursion = false
            private var prevForce = 0f
            private var prevTsNs = 0L
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val (x, y, z) = event.values
                val force = (sqrt(x * x + y * y + z * z) - 9.8f).coerceAtLeast(0f)
                val now = SystemClock.elapsedRealtime()
                // event.timestamp is the sensor's own monotonic clock — far more accurate
                // for a 5ms delta than anything measured on the delivery thread.
                var jerk = 0f
                if (prevTsNs != 0L) {
                    val dtS = (event.timestamp - prevTsNs) / 1_000_000_000f
                    if (dtS > 0f) jerk = (force - prevForce) / dtS
                }
                prevForce = force
                prevTsNs = event.timestamp
                if (jerk > maxJerk) maxJerk = jerk
                if (now - lastUiPublishAt >= FORCE_UI_PUBLISH_MS) {
                    lastUiPublishAt = now
                    currentForce = force
                }
                if (force > peakForce) peakForce = force
                // Jerk must be tracked across the WHOLE excursion, not sampled at the
                // threshold crossing. Crossing happens at the very start of the rise,
                // where even a 114G strike is still moving slowly — judging it there
                // rejected the hardest slaps outright.
                if (force > threshold.value) {
                    if (!aboveThreshold) {
                        aboveThreshold = true
                        excursionPeak = force
                        excursionJerk = 0f
                        firedThisExcursion = false
                    }
                    if (force > excursionPeak) excursionPeak = force
                    if (jerk > excursionJerk) excursionJerk = jerk
                    // Fire as soon as the rise rate qualifies, wherever in the excursion
                    // that lands — waiting for the excursion to end would add latency to
                    // a gesture the scoring clock is already timing.
                    if (!firedThisExcursion &&
                        (jerkGate.value <= 0f || excursionJerk >= jerkGate.value)
                    ) {
                        firedThisExcursion = true
                        impactCount++
                        lastJerk = excursionJerk
                        // Read before onImpact(): a successful pair consumes the touch.
                        pendingGap = detector.msSinceTouch()
                        detector.onImpact()
                    }
                } else if (aboveThreshold && force < release.value) {
                    aboveThreshold = false
                    val g = pendingGap
                    if (firedThisExcursion) {
                        logLine(
                            "IMPACT  peak=%.1f  jerk=%.0f  gap=%s".format(
                                excursionPeak, excursionJerk,
                                if (g == null) "--" else "${g}ms"
                            )
                        )
                    } else {
                        rejectedCount++
                        lastJerk = excursionJerk
                        logLine(
                            "reject  peak=%.1f  jerk=%.0f < %.0f"
                                .format(excursionPeak, excursionJerk, jerkGate.value)
                        )
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(Unit) {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val ok = try {
            sensorManager.registerListener(listener, accel, SLAP_SENSOR_PERIOD_US)
        } catch (e: SecurityException) {
            sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        }
        if (!ok) logLine("!! accelerometer unavailable")
        onDispose { sensorManager.unregisterListener(listener) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(appBackgroundBrush()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Slap Test") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { requireTouch = !requireTouch }) {
                        Text(if (requireTouch) "Touch+G" else "G only")
                    }
                    TextButton(onClick = { slidersUnlocked = !slidersUnlocked }) {
                        Text(if (slidersUnlocked) "Tuning" else "Locked")
                    }
                    TextButton(onClick = {
                        peakForce = 0f; maxPointers = 0
                        maxTouchMajor = 0f; maxContactSize = 0f
                        touchCount = 0; impactCount = 0; slapCount = 0
                        lastGap = null; maxGap = 0L; pendingGap = null
                        lastJerk = 0f; maxJerk = 0f; rejectedCount = 0
                        detector.reset(); log.clear()
                        Log.i("SlapTest", "───── RESET ─────")
                    }) { Text("Reset") }
                }
            )
        }
    ) { padding ->
        // The whole content area is the slap target, but ONLY while locked. Two hard
        // constraints force this shape:
        //  - the filter must return true; declining at ACTION_DOWN makes Android stop
        //    delivering the gesture, so ACTION_POINTER_DOWN never arrives and multitouch
        //    could never register — indistinguishable from palm rejection.
        //  - returning true consumes the event before children see it, so any control
        //    inside this Box is dead while the filter is attached.
        // Hence: locked = slap mode (filter on, sliders read-only), unlocked = tuning
        // mode (filter off, sliders live). The lock toggle sits in the top bar, outside
        // this Box, so it stays reachable in both modes.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (slidersUnlocked) Modifier else Modifier.pointerInteropFilter { e ->
                    val action = e.actionMasked
                    val live = when (action) {
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
                        MotionEvent.ACTION_POINTER_UP -> e.pointerCount - 1
                        else -> e.pointerCount
                    }
                    var maj = 0f
                    var sz = 0f
                    for (i in 0 until e.pointerCount) {
                        maj = max(maj, e.getTouchMajor(i))
                        sz = max(sz, e.getSize(i))
                    }
                    pointers = live
                    touchMajor = if (live == 0) 0f else maj
                    contactSize = if (live == 0) 0f else sz
                    if (live > maxPointers) maxPointers = live
                    if (maj > maxTouchMajor) maxTouchMajor = maj
                    if (sz > maxContactSize) maxContactSize = sz

                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                        logLine("DOWN    ptr=$live maj=%.0f sz=%.2f".format(maj, sz))
                    }
                    val isMulti = live >= 2
                    if (isMulti && !wasMulti) {
                        touchCount++
                        detector.onMultiTouch()
                        logLine("TOUCH   ptr=$live maj=%.0f".format(maj))
                    }
                    wasMulti = isMulti
                    true
                })
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "SLAP ANYWHERE",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "%.1f G".format(currentForce),
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (currentForce > threshold.value) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "peak %.1f G".format(peakForce),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    // Contact geometry — the numbers that decide whether size can stand
                    // in for pointer count when the panel rejects a palm.
                    Text(
                        "ptr $pointers (max $maxPointers)",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (maxPointers >= 2) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "maj %.0f (max %.0f)   sz %.2f (max %.2f)"
                            .format(touchMajor, maxTouchMajor, contactSize, maxContactSize),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "touches $touchCount   impacts $impactCount   SLAPS $slapCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "gap ${lastGap?.let { "${it}ms" } ?: "--"}/${maxGap}ms   " +
                            "jerk %.0f (max %.0f) G/s   rejected $rejectedCount"
                                .format(lastJerk, maxJerk),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))

                Text(
                    if (slidersUnlocked) "TUNING — touch sensing paused. Tap 'Tuning' to slap again."
                    else if (requireTouch) "LOCKED · Touch+G — needs a touch AND a spike."
                    else "LOCKED · G only — spike alone fires. Touch ignored.",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (slidersUnlocked) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (slidersUnlocked) {
                    TuningSlider("threshold", threshold.value, 2f, 40f, "%.0f G") { threshold.value = it }
                    TuningSlider("release", release.value, 1f, 20f, "%.0f G") { release.value = it }
                    TuningSlider("window", window.value, 50f, 2000f, "%.0f ms") { window.value = it }
                    TuningSlider("refractory", refractory.value, 0f, 1500f, "%.0f ms") { refractory.value = it }
                    TuningSlider("jerk gate", jerkGate.value, 0f, 20000f, "%.0f G/s") { jerkGate.value = it }
                } else {
                    // Values stay visible when locked, but nothing interactive is composed,
                    // so a palm landing here cannot drag a slider.
                    Text(
                        "thr %.0f  rel %.0f  win %.0f  ref %.0f  jerk %.0f"
                            .format(threshold.value, release.value, window.value, refractory.value, jerkGate.value),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(8.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        if (log.isEmpty()) {
                            Text(
                                "slap the screen…",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                        log.forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (line.contains("SLAP")) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    format: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                format.format(value),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.height(24.dp)
        )
    }
}
