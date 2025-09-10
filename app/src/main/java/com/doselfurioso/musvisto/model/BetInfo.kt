package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class BetInfo(
    val amount: Int,
    val bettingPlayerId: String,
    val respondingPlayerId: String,
    val isOrdago: Boolean = false,
    val pointsIfRejected: Int = 1
)
