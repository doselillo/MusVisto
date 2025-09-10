package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class LastActionInfo(
    val playerId: String,
    val action: GameAction,
    val amount: Int? = null
)