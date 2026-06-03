package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LastActionView
import com.doselfurioso.musvisto.model.toAction
import com.doselfurioso.musvisto.model.toCommand

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
            .copy(lastActionView = LastActionView(seatId, command))
        return authoritativeState
    }

    /**
     * Resuelve host-side una fase de SISTEMA que no requiere acción de jugador: la
     * declaración de PARES_CHECK/JUEGO_CHECK, forzada por las manos (cada uno tiene
     * jugada o no, sin elección). El ViewModel local la resuelve igual vía
     * `resolveDeclaration` tras los anuncios visuales; host-side no hay UI, así que
     * se resuelve directa. Devuelve true si resolvió algo (el bucle debe re-evaluar).
     */
    fun resolveSystemPhaseIfAny(): Boolean {
        return when (authoritativeState.gamePhase) {
            GamePhase.PARES_CHECK, GamePhase.JUEGO_CHECK -> {
                authoritativeState = gameLogic.resolveDeclaration(authoritativeState)
                true
            }
            else -> false
        }
    }

    /**
     * Vista redactada (fog of war) para un asiento + sus comandos legales. Los
     * comandos son el espejo serializable de `availableActions` (`@Transient` → no
     * viaja por la red, el cliente NO los puede recalcular): el host los proyecta a
     * [GameCommand] por asiento. Solo el asiento de TURNO recibe comandos; los
     * demás, lista vacía (mismo gating que el juego local, `currentTurnPlayerId`).
     */
    fun viewFor(seatId: String): GameState {
        val redacted = StateRedactor.redactFor(seatId, authoritativeState)
        val commands = if (seatId == authoritativeState.currentTurnPlayerId) {
            authoritativeState.availableActions.mapNotNull { it.toCommand() }
        } else {
            emptyList()
        }
        return redacted.copy(availableCommands = commands)
    }
}
