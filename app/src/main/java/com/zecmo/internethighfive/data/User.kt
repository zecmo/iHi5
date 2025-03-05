package com.zecmo.internethighfive.data

data class User(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val lastLoginTimestamp: Long = 0L,
    val friendIds: List<String> = emptyList(),
    val handRaised: Boolean = false,
    val raisedHandTimestamp: Long = 0L
) {
    // Empty constructor for Firebase
    constructor() : this("", "", "", 0L, emptyList(), false, 0L)

    companion object {
        const val ONLINE_THRESHOLD = 5000L // 5 seconds
        const val HAND_RAISED_THRESHOLD = 5000L // 5 seconds
    }

    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastLoginTimestamp < ONLINE_THRESHOLD

    val hasActiveHighFive: Boolean
        get() = handRaised || (System.currentTimeMillis() - raisedHandTimestamp < HAND_RAISED_THRESHOLD)
} 