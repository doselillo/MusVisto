package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

/**
 * Ajustes de reglas de una partida (#29). Pensado para crecer: nuevas opciones
 * (p. ej. si los treses cuentan como reyes / los doses como ases, u otras
 * costumbres) se añaden como CAMPOS nuevos con default, sin rearquitectura.
 *
 * `bestOfChicos` debe ser impar (mejor de 3 / 5). Una "vaca" (partida) la gana
 * quien primero gane `chicosToWinVaca` chicos; cada chico se juega a
 * `pointsPerChico` tantos.
 */
@Serializable
data class GameSettings(
    val bestOfChicos: Int = 3,
    val pointsPerChico: Int = 40
) {
    /** Chicos necesarios para ganar la vaca: ceil(bestOfChicos / 2). */
    val chicosToWinVaca: Int get() = bestOfChicos / 2 + 1
}
