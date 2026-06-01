package com.doselfurioso.musvisto.presentation

import androidx.annotation.DrawableRes
import com.doselfurioso.musvisto.R

/**
 * Personaje seleccionable de la mesa (#34 / #36): identidad VISUAL pura
 * (nombre + avatar). La personalidad de juego ([com.doselfurioso.musvisto.logic.AIArchetype])
 * ya NO va soldada al personaje: se elige por ASIENTO en la pantalla de selección
 * y se persiste en `GameSettings`. Así el usuario combina libremente cara y estilo.
 */
data class Character(
    val id: String,
    val name: String,
    @DrawableRes val avatarResId: Int
)

/**
 * Catálogo de personajes. 4 avatares reales + 2 placeholders (círculos de color)
 * — el sistema crece añadiendo entradas con su avatar cuando haya más arte. Los
 * ids son estables (se persisten en `GameSettings`).
 */
object CharacterRoster {

    val all: List<Character> = listOf(
        Character(id = "castilla", name = "Onésimo", avatarResId = R.drawable.avatar_castilla),
        Character(id = "aragon", name = "Fernando", avatarResId = R.drawable.avatar_aragon),
        Character(id = "navarra", name = "Sancho", avatarResId = R.drawable.avatar_navarra),
        Character(id = "granada", name = "Miramamolín", avatarResId = R.drawable.avatar_granada),
        Character(id = "placeholder_1", name = "Invitado 1", avatarResId = R.drawable.avatar_placeholder_1),
        Character(id = "placeholder_2", name = "Invitado 2", avatarResId = R.drawable.avatar_placeholder_2)
    )

    private val byId = all.associateBy { it.id }

    /** Personaje por id; cae al primero del roster si el id no existe (saves viejos). */
    fun byId(id: String): Character = byId[id] ?: all.first()
}
