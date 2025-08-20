package com.doselfurioso.musvisto.model

data class BetInfo(
    val amount: Int = 2, // Default bet is 2
    val bettingPlayerId: String,
    val isOrdago: Boolean = false
)
