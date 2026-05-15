package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
data class LastActionInfo(
    val playerId: String,
    val action: GameAction,
    val amount: Int? = null,
    // Identificador único por instancia. Distingue dos acciones idénticas
    // (p. ej. "Paso" en Grande y "Paso" en Chica) para que la limpieza de
    // anuncios entre lances no borre por error una acción nueva con el mismo
    // contenido que una antigua. Se asigna en cada construcción.
    val seq: Long = System.nanoTime()
)