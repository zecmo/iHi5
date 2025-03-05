package com.zecmo.internethighfive.data

data class HighFiveSession(
    val id: String = "",
    val userId1: String = "",
    val userId2: String = "",
    val isUser1Ready: Boolean = false,
    val isUser2Ready: Boolean = false,
    val lastUpdated: Long = 0L
) {
    // Empty constructor for Firebase
    constructor() : this("", "", "", false, false, 0L)
} 