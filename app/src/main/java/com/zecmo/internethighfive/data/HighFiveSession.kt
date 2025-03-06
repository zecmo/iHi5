package com.zecmo.internethighfive.data

data class HighFiveSession(
    val id: String = "",
    val initiatorId: String = "",
    val initiatorUsername: String = "",
    val partnerId: String = "",
    val partnerUsername: String = "",
    val initiatorTimestamp: Long = 0L,
    val partnerTimestamp: Long = 0L,
    val lastUpdated: Long = 0L,
    val completed: Boolean = false,
    val quality: String = "",
) {
    // Empty constructor for Firebase
    constructor() : this("", "", "", "", "", 0L, 0L, 0L, false, "")
} 