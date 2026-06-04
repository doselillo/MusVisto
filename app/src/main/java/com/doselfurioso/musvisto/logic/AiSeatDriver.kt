package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.toCommand

/**
 * Conduce los asientos de **IA** host-side (multijugador): cuando el turno es de
 * una IA, consulta su [AILogic] (la MISMA que en local) y produce el [GameCommand]
 * correspondiente, de modo que la acción de la IA viaja por el MISMO camino que la
 * de un cliente de red (`MatchHost.submitCommand`).
 *
 * Replica el núcleo de decisión de `GameViewModel.handleAiTurn` **sin** los
 * `delay()` de pacing (host-side el ritmo lo da el turn timer / animación del
 * cliente, Fase 3) y **sin** la orquestación de señas (Fase 4). Para fases sin
 * acción del actor de turno (ROUND_OVER/GAME_OVER, o turno de un humano) devuelve
 * `null`: el bucle del host se detiene y espera (transición de ronda y arranque
 * son responsabilidad del orquestador, como en el simulador).
 *
 * **AFK / cede a IA (Fase 3, turn timer rebanada 2):** [aiLogics] puede llevar cerebros
 * de TODOS los asientos (también humanos); un asiento humano solo se conduce si está en
 * [afkSeats] (lo decide el orquestador tras agotar el timeout). Así el cerebro existe pero
 * NO juega por un humano presente: solo cuando lleva rato ausente.
 */
class AiSeatDriver(
    private val aiLogics: Map<String, AILogic>
) {
    /** ¿Hay cerebro para conducir [seatId] si se ausenta? (lo consulta el orquestador). */
    fun canDrive(seatId: String): Boolean = seatId in aiLogics

    fun commandFor(state: GameState, afkSeats: Set<String> = emptySet()): Pair<String, GameCommand>? {
        if (state.gamePhase == GamePhase.ROUND_OVER || state.gamePhase == GamePhase.GAME_OVER) return null
        val turnId = state.currentTurnPlayerId ?: return null
        val player = state.players.find { it.id == turnId } ?: return null
        // Conduce IA reales SIEMPRE; humanos SOLO si están marcados AFK (cede a IA).
        if (!player.isAi && turnId !in afkSeats) return null
        val ai = aiLogics[turnId] ?: return null

        val decision = ai.makeDecision(state, player)
        val command = decision.action.toCommand(decision.cardsToDiscard) ?: return null
        return turnId to command
    }
}
