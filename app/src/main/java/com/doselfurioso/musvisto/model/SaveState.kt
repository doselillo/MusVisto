package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class SaveState(
    val teamAScore: Int,
    val teamBScore: Int,
    val lastManoPlayerId: String
)