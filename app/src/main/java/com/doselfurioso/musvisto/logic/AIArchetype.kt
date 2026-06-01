package com.doselfurioso.musvisto.logic

/**
 * Arquetipo de personalidad de IA (#34): nombre legible + descripción de estilo
 * + el [AIProfile] de palancas que lo materializa. En Fase A/B todos los perfiles
 * == baseline (`EQUILIBRADO`), así que asignar arquetipos NO cambia el juego
 * todavía; los deltas reales se calibran en Fase C (simulador + reviewers +
 * playtest). La pantalla de selección ya persiste la elección por asiento.
 *
 * El humano no usa arquetipo (juega a mano); se asigna a los 3 rivales de IA.
 */
enum class AIArchetype(
    val displayName: String,
    /** Línea de estilo que se muestra al rotar la personalidad en la selección de mesa. */
    val description: String,
    val profile: AIProfile
) {
    EQUILIBRADO("Equilibrado", "Juega sólido y equilibrado.", AIProfile.EQUILIBRADO),
    AGRESIVO("Agresivo", "Abre y sube a menudo.", AIProfile.AGRESIVO),
    CONSERVADOR("Conservador", "Va sobre seguro; musea mucho.", AIProfile.CONSERVADOR),
    FAROLERO("Farolero", "Farolea a menudo; impredecible.", AIProfile.FAROLERO);

    /** Siguiente arquetipo en orden cíclico (flecha ›). */
    fun next(): AIArchetype {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    /** Arquetipo anterior en orden cíclico (flecha ‹). */
    fun prev(): AIArchetype {
        val all = values()
        return all[(ordinal - 1 + all.size) % all.size]
    }

    companion object {
        /** Arquetipo por nombre; cae a EQUILIBRADO si no existe (saves viejos / id basura). */
        fun byName(name: String): AIArchetype = values().firstOrNull { it.name == name } ?: EQUILIBRADO
    }
}
