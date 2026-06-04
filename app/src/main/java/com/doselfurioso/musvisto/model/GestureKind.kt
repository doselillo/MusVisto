package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

/**
 * Las 8 señas del Mus como CLAVE estable, en vez de un `R.drawable: Int`. Un resId
 * es un recurso Android que no debe viajar por la red (fue el crash del avatar) ni
 * vivir en el motor (Fase 0.5 lo dejó libre de `android.*`). Con esta clave:
 *  - `MusGameLogic.determineGesture(hand)` deriva la seña de la mano (host-side incluido),
 *  - `AILogic` la interpreta (significado, boosts, interceptación),
 *  - la UI mapea `kind → drawable` (igual que el avatar por asiento en `adaptOnlineView`).
 *
 * [label] = nombre legible para logs/depuración, mismo patrón que `GameAction.displayText`.
 */
@Serializable
enum class GestureKind(val label: String) {
    REYES_2("Dos Reyes"),
    REYES_3("Tres Reyes"),
    ASES_2("Dos Ases"),
    ASES_3("Tres Ases"),
    JUEGO_31("31 de Juego"),
    CIEGA("Ciega"),
    DUPLES_ALTOS("Duples Altos"),
    DUPLES_BAJOS("Duples Bajos")
}
