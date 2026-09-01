package com.zecmo.internethighfive.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    @SerialName("last_login_at")
    val lastLoginAt: Long = 0L,
    @SerialName("hand_raised")
    val handRaised: Boolean = false,
    @SerialName("raised_hand_at")
    val raisedHandAt: Long = 0L,
    @SerialName("current_session")
    val currentSession: String = ""
) {
    companion object {
        const val ONLINE_THRESHOLD = 60_000L        // 60 seconds — tolerates device clock skew
        const val HAND_RAISED_THRESHOLD = 300_000L  // 5 minutes
    }

    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastLoginAt < ONLINE_THRESHOLD

    val hasActiveHighFive: Boolean
        get() = handRaised && (System.currentTimeMillis() - raisedHandAt < HAND_RAISED_THRESHOLD)

    /**
     * Bounded by liveness on purpose. `current_session` is only ever cleared by the
     * owner's own device — on leaving the high-five screen, or on next app launch — so
     * a kill, crash, or dropped write leaves the row set indefinitely and everyone else
     * sees a permanent "High Fiving!". Someone whose heartbeat has stopped cannot be in
     * a session, so treat the flag as expired once they are offline.
     */
    val isInSession: Boolean
        get() = currentSession.isNotEmpty() && isOnline
}
