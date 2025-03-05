package com.zecmo.internethighfive.data

data class User(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val lastLoginTimestamp: Long = 0L,
    val friendIds: List<String> = emptyList()
) {
    // Empty constructor for Firebase
    constructor() : this("", "", "", 0L, emptyList())

    companion object {
        const val ONLINE_THRESHOLD = 5000L // 5 seconds
    }

    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastLoginTimestamp < ONLINE_THRESHOLD
} 