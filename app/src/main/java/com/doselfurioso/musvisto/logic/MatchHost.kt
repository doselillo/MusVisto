package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.toAction

/**
 * Núcleo **host-autoritativo** del multijugador (prep, sin red).
 *
 * Mantiene el `GameState` COMPLETO y aplica los comandos de los asientos a través
 * del reducer ya existente (`MusGameLogic.processAction`), exponiendo una **vista
 * redactada por asiento** (fog of war). Es exactamente la pieza que un
 * `FirebaseMatchHost` envolvería: el listener de `actions/{seatId}` llamaría a
 * [submitCommand] y luego escribiría `viewFor(s)` en `views/{s}` para cada asiento.
 *
 * Compone los tres primitivos de la Fase 0:
 *  - [GameCommand] (0.1) como entrada serializable de red,
 *  - `MusGameLogic.processAction` (reducer puro) como autoridad,
 *  - [StateRedactor] (0.3) como frontera de información de salida.
 *
 * **Alcance deliberado:** la rebanada APLICAR-COMANDO + REDACTAR. NO orquesta los
 * turnos de IA ni el pacing por `delay()` (eso vive en `GameViewModel` y se
 * reescribe a flujo de red + turn timers en la Fase 3). **Autorización mínima**
 * (¿puede este asiento esta acción AHORA?) queda como Fase 3: usaría las
 * `availableActions` recalculadas por asiento, hoy `@Transient`. Kotlin puro:
 * sin Android, sin Firebase → testeable y reusable host/server-side.
 */
class MatchHost(
    private val gameLogic: MusGameLogic,
    initialState: GameState
) {
    var authoritativeState: GameState = initialState
        private set

    /**
     * Aplica el comando de [seatId] al estado autoritativo. El descarte es
     * autónomo (lleva sus cartas), que se inyectan en `selectedCardsForDiscard`
     * antes de reducir, igual que hace hoy el flujo de la IA.
     */
    fun submitCommand(seatId: String, command: GameCommand): GameState {
        val stateForAction = when (command) {
            is GameCommand.Discard -> authoritativeState.copy(
                selectedCardsForDiscard = command.cards.toSet()
            )
            else -> authoritativeState
        }
        authoritativeState = gameLogic.processAction(stateForAction, command.toAction(), seatId)
        return authoritativeState
    }

    /** Vista redactada (fog of war) para un asiento: lo que el host enviaría a ese cliente. */
    fun viewFor(seatId: String): GameState = StateRedactor.redactFor(seatId, authoritativeState)
}
