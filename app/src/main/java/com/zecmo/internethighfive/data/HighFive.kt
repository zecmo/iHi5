package com.zecmo.internethighfive.data

data class HighFive(
    val id: String = "",
    val initiatorId: String = "",
    val receiverId: String = "",
    val initiatorTimestamp: Long = 0,
    val receiverTimestamp: Long = 0,
    val status: String = "pending", // pending, matched, expired, completed
    val quality: Float = 0f,
    val createdAt: Long = System.currentTimeMillis()
) {
    // Empty constructor for Firebase
    constructor() : this("", "", "", 0, 0, "pending", 0f, 0)

    fun isComplete(): Boolean = status == "completed"
    fun isPending(): Boolean = status == "pending"
    fun isExpired(): Boolean = status == "expired"
    fun isMatched(): Boolean = status == "matched"
    
    fun getTimeDifference(): Long = kotlin.math.abs(initiatorTimestamp - receiverTimestamp)
} 