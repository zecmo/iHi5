package com.zecmo.internethighfive.ui

import android.os.Build
import android.os.SystemClock
import com.zecmo.internethighfive.BuildConfig
import kotlin.math.abs

// Slap gesture tuning. Threshold is in m/s² above gravity: the old 3f was a gentle
// jiggle — a real hand slap reads far higher, but a phone held in a soft grip absorbs
// a lot of it, so these want empirical tuning on real devices. SlapTestScreen exists
// to find these numbers; whatever it settles on should be written back here.
internal const val SLAP_FORCE_THRESHOLD = 20f
internal const val SLAP_FORCE_RELEASE = 6f
internal const val SLAP_WINDOW_MS = 250L
// One slap rings the chassis and crosses the threshold 2-3 times ~30ms apart. Without a
// refractory period that reads as several slaps.
internal const val SLAP_REFRACTORY_MS = 500L
// Peak rise rate (G/s) across the force excursion — the ONLY reliable slap-vs-shake
// discriminator found. Measured over 20 slaps and 20 deliberate shakes on a Pixel 6 Pro:
//
//            peak force        jerk
//   slaps    53.7 - 121.1 G    10,371 - 21,748 G/s
//   shakes   39.2 -  60.4 G     1,040 -  2,186 G/s
//
// Peak force OVERLAPS (a hard shake out-peaks a soft slap) so it cannot separate them;
// jerk has a 4.7x gap with nothing in between. 5000 sits mid-gap with ~2x margin either
// side and classified all 40 samples correctly. 0 disables the check.
// Re-measure with SlapTestScreen if this ever needs revisiting on other hardware.
internal const val SLAP_JERK_THRESHOLD = 5_000f
// 5000us = 200Hz, the ceiling permitted with HIGH_SAMPLING_RATE_SENSORS. An explicit
// period rather than SENSOR_DELAY_FASTEST (0us), which means "as fast as the hardware
// allows" — uncapped, battery-hungry, and a SecurityException on API 31+.
internal const val SLAP_SENSOR_PERIOD_US = 5_000
// The sensor runs far faster than the display. Detection uses every sample, but the
// force visual only needs ~60Hz — publishing all 200 would recompose the screen on
// every sample for no visible gain.
internal const val FORCE_UI_PUBLISH_MS = 16L

/**
 * An emulator has no way to produce a real accelerometer impact, so the slap gesture is
 * physically unreachable there. Debug builds on an emulator accept a plain single tap
 * instead, purely so the rest of the flow (scoring, SFX, result screen) stays testable.
 *
 * Deliberately NOT just BuildConfig.DEBUG: a debug build on real hardware must still
 * require a genuine slap, since that is the thing we need to tune.
 */
internal val isEmulator: Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
    Build.FINGERPRINT.startsWith("unknown") ||
    Build.MODEL.contains("google_sdk") ||
    Build.MODEL.contains("Emulator") ||
    Build.MODEL.contains("Android SDK built for") ||
    Build.MODEL.startsWith("sdk_gphone") ||
    Build.MANUFACTURER.contains("Genymotion") ||
    Build.HARDWARE.contains("goldfish") ||
    Build.HARDWARE.contains("ranchu") ||
    (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))

internal val emulatorTapBypass: Boolean = BuildConfig.DEBUG && isEmulator

/**
 * Correlates the two halves of a slap — fingers on glass and an impact spike — which
 * arrive milliseconds apart rather than together. Whichever lands second completes the
 * gesture, so long as its partner arrived within [windowMs].
 *
 * Uses elapsedRealtime (monotonic) so it is unaffected by wall-clock adjustments.
 *
 * Shared verbatim by HighFiveScreen and SlapTestScreen — the tuner is only meaningful
 * if it exercises the same correlation the real screen runs.
 */
internal class SlapDetector(
    var windowMs: Long = SLAP_WINDOW_MS,
    /**
     * When false, an impact alone completes the gesture.
     *
     * Defaults to false, from measurement: a real slap very often produces no touch
     * event at all, because the panel never resolves a contact from a fast palm strike
     * on a phone that is free to move. When it does register it is a single pointer, so
     * the multitouch condition this was built around effectively never occurs. Slap-vs-
     * shake discrimination comes from jerk instead — see [SLAP_JERK_THRESHOLD].
     */
    var requireTouch: Boolean = false,
    var refractoryMs: Long = SLAP_REFRACTORY_MS
) {
    private var lastTouchAt = 0L
    private var lastImpactAt = 0L
    private var lastSlapAt = 0L
    var onSlap: (() -> Unit)? = null

    fun onMultiTouch() {
        lastTouchAt = SystemClock.elapsedRealtime()
        if (requireTouch) checkPair()
    }

    fun onImpact() {
        lastImpactAt = SystemClock.elapsedRealtime()
        if (requireTouch) checkPair() else fire()
    }

    /** ms since the last multitouch, or null if none pending — the gap a window must span. */
    fun msSinceTouch(): Long? =
        if (lastTouchAt == 0L) null else SystemClock.elapsedRealtime() - lastTouchAt

    /** Gap between the two halves of the most recent pairing attempt, for diagnostics. */
    fun pendingGapMs(): Long? =
        if (lastTouchAt == 0L || lastImpactAt == 0L) null
        else abs(lastTouchAt - lastImpactAt)

    fun reset() {
        lastTouchAt = 0L
        lastImpactAt = 0L
        lastSlapAt = 0L
    }

    private fun checkPair() {
        if (lastTouchAt == 0L || lastImpactAt == 0L) return
        if (abs(lastTouchAt - lastImpactAt) > windowMs) return
        fire()
    }

    private fun fire() {
        val now = SystemClock.elapsedRealtime()
        // Swallow the chassis ringing that follows a single strike.
        if (now - lastSlapAt < refractoryMs) return
        lastSlapAt = now
        // Consume both so one slap can't re-fire off a later stray signal.
        lastTouchAt = 0L
        lastImpactAt = 0L
        onSlap?.invoke()
    }
}
