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

    val isInSession: Boolean
        get() = currentSession.isNotEmpty()
}
