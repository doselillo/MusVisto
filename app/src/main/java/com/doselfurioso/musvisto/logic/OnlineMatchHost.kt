package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.RoomSeat
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

/**
 * Arranque host-side de una partida ONLINE (Fase 3). Toma los asientos de la sala
 * (lobby, Fase 2), reparte un estado inicial y monta el bucle autoritativo
 * ([MatchHost] + [AiSeatDriver]) sobre un [MatchTransport] (la implementación
 * Firebase en producción). Reutiliza el MISMO motor que el modo local y el
 * simulador; lo único nuevo es que el estado redactado viaja por la red.
 *
 * Equivale a `GameViewModel.startNewGame` pero (a) construye los jugadores desde
 * los ASIENTOS de la sala (no desde `GameSettings`), y (b) publica vistas
 * redactadas por el transporte en vez de un único `_gameState` local.
 *
 * Cubierto desde Fase 3c: **transiciones de ronda** (al llegar a ROUND_OVER el host
 * puntúa, muestra el resultado y reparte la siguiente cuando un humano pulsa "Continuar";
 * ver [MatchHostService]). NO cubierto aún (Fase 4 / 5): señas (`onEnterMusPhase` no se
 * aplica → sin delegación de corte de Mus online), turn timers/AFK y vacas/multi-chico
 * (llegar al chico = fin de partida).
 */
class OnlineMatchHost(
    private val gameLogic: MusGameLogic,
    private val transport: MatchTransport,
    private val scope: CoroutineScope,
    private val pacingMs: Long = DEFAULT_PACING_MS,
    private val roundOverPacingMs: Long = DEFAULT_ROUND_OVER_PACING_MS,
    private val log: (String) -> Unit = {},
    private val rng: Random = Random(System.currentTimeMillis())
) {
    private var service: MatchHostService? = null

    /** Reparte y arranca el bucle host↔clientes. [seats] = los 4 asientos de la sala. */
    fun start(seats: List<RoomSeat>, settings: GameSettings) {
        val players = seats.map { it.toPlayer() }
        val seatIds = players.map { it.id }
        val manoId = players.first().id
        val shuffled = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, remaining) = gameLogic.dealCards(players, shuffled, manoId)

        val initial = GameState(
            players = dealt,
            deck = remaining,
            settings = settings,
            gamePhase = GamePhase.MUS,
            manoPlayerId = manoId,
            currentTurnPlayerId = manoId,
            playersInLance = seatIds.toSet(),
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )

        val aiLogics = seats.filter { it.isAi }.associate { seat ->
            seat.seatId to AILogic(gameLogic, rng, AIArchetype.byName(seat.archetype.orEmpty()).profile)
        }

        service = MatchHostService(
            host = MatchHost(gameLogic, initial),
            transport = transport,
            seatIds = seatIds,
            aiDriver = AiSeatDriver(aiLogics),
            scope = scope,
            pacing = MatchPacing(turnMs = pacingMs, roundOverMs = roundOverPacingMs),
            log = log
        ).also { it.start() }
    }

    private fun RoomSeat.toPlayer(): Player = Player(
        id = seatId,
        name = if (isAi) "IA" else displayName.ifBlank { "Jugador" },
        avatarResId = 0,
        isAi = isAi,
        team = team
    )

    private companion object {
        /** Ritmo (ms) de las acciones de IA host-side para que el cliente las vea. */
        const val DEFAULT_PACING_MS = 800L
        /** Pausa (ms) del "fin de ronda" visible antes de repartir la siguiente. */
        const val DEFAULT_ROUND_OVER_PACING_MS = 3000L
    }
}
