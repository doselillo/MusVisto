package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class BetInfo(
    val amount: Int, // Default bet is 2
    val bettingPlayerId: String,
    val respondingPlayerId: String,
    val isOrdago: Boolean = false
)
