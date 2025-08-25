package com.doselfurioso.musvisto.model

// Representa el resultado final de un lance en la ronda.
data class LanceResult(
    val lance: GamePhase,
    val outcome: String, // "Paso", "Querido", "No Querido"
    val amount: Int? = null // La cantidad de la apuesta si la hubo
)
