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
    val pointsPerChico: Int = 40,
    // #34/#36 Selección de mesa. Solo IDs (referencian CharacterRoster); el
    // modelo Player no se toca. Defaults = la mesa clásica (humano Castilla,
    // pareja Aragón, rivales Navarra/Granada) → saves previos cargan igual.
    val humanName: String = "Tú",
    val humanCharacterId: String = "castilla",
    val partnerCharacterId: String = "aragon",
    val rivalLeftCharacterId: String = "navarra",
    val rivalRightCharacterId: String = "granada",
    // #34 Personalidad por asiento de IA (clave = AIArchetype.name). String para
    // no acoplar model->logic ni complicar la serialización; se resuelve con
    // AIArchetype.byName. El humano NO tiene (juega a mano). Defaults variados =
    // mesa de fábrica con mezcla de estilos. El EFECTO real lo conecta la Fase C
    // (GameViewModel.profileFor); hoy se persiste la elección pero todos juegan baseline.
    val partnerArchetype: String = "CONSERVADOR",
    val rivalLeftArchetype: String = "AGRESIVO",
    val rivalRightArchetype: String = "FAROLERO",
    // #36 Color del tapete de la mesa. Clave -> Color en la capa UI (TableTheme).
    val tableColor: String = "GREEN",
    // Consentimiento de telemetría de cuelgues (Crashlytics). Opt-out: activo por
    // defecto (interés legítimo: estabilidad), desactivable en Opciones. La seam
    // CrashReporting traduce este flag a FirebaseCrashlytics; MusVistoApp lo aplica
    // al arrancar y el toggle lo aplica al instante. Default true → saves previos
    // (sin el campo) cargan con la telemetría activada.
    val crashReportingEnabled: Boolean = true
) {
    /** Chicos necesarios para ganar la vaca: ceil(bestOfChicos / 2). */
    val chicosToWinVaca: Int get() = bestOfChicos / 2 + 1
}
