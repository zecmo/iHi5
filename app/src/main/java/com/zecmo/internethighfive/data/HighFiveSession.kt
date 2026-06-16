package com.zecmo.internethighfive.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HighFiveSession(
    val id: String = "",
    @SerialName("initiator_id")
    val initiatorId: String = "",
    @SerialName("initiator_username")
    val initiatorUsername: String = "",
    @SerialName("partner_id")
    val partnerId: String? = null,
    @SerialName("partner_username")
    val partnerUsername: String = "",
    @SerialName("initiator_timestamp")
    val initiatorTimestamp: Long = 0L,
    @SerialName("partner_timestamp")
    val partnerTimestamp: Long = 0L,
    @SerialName("last_updated")
    val lastUpdated: Long = 0L,
    val completed: Boolean = false,
    val quality: String = ""
)
