package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class LanceResult(
    val lance: GamePhase,
    val outcome: String, // "Paso", "Querido", "No Querido"
    val amount: Int? = null // La cantidad de la apuesta si la hubo
)
