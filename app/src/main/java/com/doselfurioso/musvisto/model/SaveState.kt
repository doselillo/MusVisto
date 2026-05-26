package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class SaveState(
    val teamAScore: Int,
    val teamBScore: Int,
    val lastManoPlayerId: String,
    // #29 vacas: chicos ganados en la vaca en curso y reglas con las que se
    // empezó. Defaults → los saves anteriores (sin estos campos) se cargan como
    // vaca 0-0 a mejor de 3.
    val chicosWonA: Int = 0,
    val chicosWonB: Int = 0,
    val bestOfChicos: Int = 3
)