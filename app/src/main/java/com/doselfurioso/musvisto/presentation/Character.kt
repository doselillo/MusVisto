package com.doselfurioso.musvisto.presentation

import androidx.annotation.DrawableRes
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AIArchetype

/**
 * Personaje seleccionable de la mesa (#34 / #36): identidad visible (nombre +
 * avatar) + su [AIArchetype] de juego + las 6 barras de rasgos que se muestran
 * en la pantalla de selección.
 *
 * Las barras son **presentación** (un retrato del estilo); la lógica real la
 * aporta `archetype.profile`. El humano usa un personaje solo por su avatar/
 * nombre — su `archetype` se ignora al jugar a mano.
 */
data class Character(
    val id: String,
    val name: String,
    @DrawableRes val avatarResId: Int,
    val archetype: AIArchetype,
    /** Valores 0..100 en el orden de [CharacterRoster.TRAIT_AXES]. */
    val traits: List<Int>
)

/**
 * Catálogo de personajes. Fase B: 4 avatares reales + 2 placeholders (círculos
 * de color) — el sistema crece añadiendo entradas con su avatar cuando haya
 * más arte. Los ids son estables (se persisten en `GameSettings`).
 */
object CharacterRoster {

    /** Ejes de personalidad (orden fijo; coincide con [Character.traits]). */
    val TRAIT_AXES = listOf(
        "Osadía", "Faroleo", "Caza señas", "Corta mus", "Descartes", "Interactividad"
    )

    val all: List<Character> = listOf(
        Character(
            id = "castilla",
            name = "Castilla",
            avatarResId = R.drawable.avatar_castilla,
            archetype = AIArchetype.EQUILIBRADO,
            traits = listOf(50, 45, 50, 50, 55, 50)
        ),
        Character(
            id = "aragon",
            name = "Aragón",
            avatarResId = R.drawable.avatar_aragon,
            archetype = AIArchetype.CONSERVADOR,
            traits = listOf(30, 25, 45, 30, 60, 40)
        ),
        Character(
            id = "navarra",
            name = "Navarra",
            avatarResId = R.drawable.avatar_navarra,
            archetype = AIArchetype.AGRESIVO,
            traits = listOf(80, 55, 55, 75, 50, 65)
        ),
        Character(
            id = "granada",
            name = "Granada",
            avatarResId = R.drawable.avatar_granada,
            archetype = AIArchetype.FAROLERO,
            traits = listOf(65, 85, 60, 55, 45, 70)
        ),
        Character(
            id = "placeholder_1",
            name = "Invitado 1",
            avatarResId = R.drawable.avatar_placeholder_1,
            archetype = AIArchetype.AGRESIVO,
            traits = listOf(75, 60, 50, 70, 50, 55)
        ),
        Character(
            id = "placeholder_2",
            name = "Invitado 2",
            avatarResId = R.drawable.avatar_placeholder_2,
            archetype = AIArchetype.CONSERVADOR,
            traits = listOf(35, 30, 40, 35, 55, 45)
        )
    )

    private val byId = all.associateBy { it.id }

    /** Personaje por id; cae al primero del roster si el id no existe (saves viejos). */
    fun byId(id: String): Character = byId[id] ?: all.first()
}
