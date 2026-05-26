package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class LanceResult(
    val lance: GamePhase,
    val outcome: String, // "Paso", "Querido", "No Querido"
    val amount: Int? = null, // La cantidad de la apuesta si la hubo
    // Solo para "No Querido": equipo que se llevó los tantos al instante (el que
    // envidó). En "Querido"/"Paso" el ganador se resuelve en el showdown de fin
    // de ronda, así que aquí queda null.
    val winningTeam: String? = null
)
