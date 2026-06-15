package com.zecmo.internethighfive.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HighFive(
    val id: String = "",
    @SerialName("initiator_id")
    val initiatorId: String = "",
    @SerialName("receiver_id")
    val receiverId: String = "",
    @SerialName("initiator_timestamp")
    val initiatorTimestamp: Long = 0L,
    @SerialName("receiver_timestamp")
    val receiverTimestamp: Long = 0L,
    val status: String = "pending", // pending | matched | completed | expired
    val quality: Float = 0f
) {
    fun isPending(): Boolean = status == "pending"
    fun isMatched(): Boolean = status == "matched"
    fun isCompleted(): Boolean = status == "completed"
    fun isExpired(): Boolean = status == "expired"

    fun getTimeDifference(): Long = kotlin.math.abs(initiatorTimestamp - receiverTimestamp)
}
