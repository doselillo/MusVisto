package com.doselfurioso.musvisto.logic

/**
 * Arquetipo de personalidad de IA (#34): nombre legible + el [AIProfile] de
 * palancas que lo materializa. En Fase A/B todos los perfiles == baseline
 * (`EQUILIBRADO`), así que asignar arquetipos NO cambia el juego todavía; los
 * deltas reales se calibran en Fase C (simulador + reviewers + playtest).
 *
 * El humano no usa arquetipo (juega a mano); se asigna a los 3 rivales de IA.
 */
enum class AIArchetype(val displayName: String, val profile: AIProfile) {
    EQUILIBRADO("Equilibrado", AIProfile.EQUILIBRADO),
    AGRESIVO("Agresivo", AIProfile.AGRESIVO),
    CONSERVADOR("Conservador", AIProfile.CONSERVADOR),
    FAROLERO("Farolero", AIProfile.FAROLERO);
}
