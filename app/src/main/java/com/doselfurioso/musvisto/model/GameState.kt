package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class GamePhase {
    PRE_GAME, MUS, DISCARD, GRANDE, CHICA, PARES_CHECK, PARES, JUEGO_CHECK, JUEGO, ROUND_OVER, GAME_OVER
}
@Serializable
enum class GameEvent {
    DISCARD_PILE_SHUFFLED
}
@Serializable
data class GameState(
    val players: List<Player> = emptyList(),
    val deck: List<Card> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val currentTurnPlayerId: String? = null,
    val gamePhase: GamePhase = GamePhase.PRE_GAME,
    val isPuntoPhase: Boolean = false,
    val score: Map<String, Int> = mapOf("teamA" to 0, "teamB" to 0),
    @Transient val availableActions: List<GameAction> = emptyList(),
    val playersWhoPassed: Set<String> = emptySet(),
    val currentBet: BetInfo? = null,
    val agreedBets: Map<GamePhase, Int> = emptyMap(),
    val discardCounts: Map<String, Int> = emptyMap(),
    val selectedCardsForDiscard: Set<Card> = emptySet(),
    // #29 vacas: winningTeam = la VACA (partida) terminó, este equipo la ganó.
    // chicoJustWon = se acaba de ganar un chico pero la vaca SIGUE (al Continuar
    // se arranca un chico nuevo). Mutuamente excluyentes.
    val winningTeam: String? = null,
    val chicosWon: Map<String, Int> = mapOf("teamA" to 0, "teamB" to 0),
    val chicoJustWon: String? = null,
    val settings: GameSettings = GameSettings(),
    val manoPlayerId: String = "",
    @Transient val lastAction: LastActionInfo? = null,
    val event: GameEvent? = null,
    val betInitiatorTeam: String? = null,
    val playersPendingResponse: List<String> = emptyList(),
    val revealAllHands: Boolean = false,
    val roundHistory: List<LanceResult> = emptyList(),
    @Transient val actionLog: List<LastActionInfo> = emptyList(),
    val noMusPlayer: String? = null,
    val isNewLance: Boolean = true,
    @Transient val currentLanceActions: Map<String, LastActionInfo> = emptyMap(),
    val scoreBreakdown: ScoreBreakdown? = null,
    val scoreEvents: List<ScoreEventInfo> = emptyList(),
    val ordagoInfo: OrdagoInfo? = null,
    @Transient val isSelectingBet: Boolean = false,
    val activeGesture: ActiveGestureInfo? = null,
    val knownGestures: Map<String, ActiveGestureInfo> = emptyMap(),
    // Pre-decisión por IA: si va a pasar seña en este Mus, qué seña (GestureKind).
    // null/ausente = no pasa. Permite que AILogic y triggerAiGestures
    // (corrutinas separadas) decidan coherentemente: si NO voy a señalizar,
    // no delego el corte ni apoyo envites — mi compañero humano no tendrá
    // info, así que juego mi mano normal. Se asigna al entrar a MUS y se
    // limpia al salir.
    val pendingGestures: Map<String, GestureKind> = emptyMap(),
    val playersInLance: Set<String> = emptySet(),
    @Transient val isPaused: Boolean = false,
    // Mus corrido: 1ª ronda del juego, el que corta el mus se convierte en
    // mano. Activo hasta el primer corte; mientras, sin señas (#17).
    val musCorrido: Boolean = false,
    // #37 "Fatiga de Mus": nº de ciclos Mus+descarte completados en esta ronda
    // SIN que nadie corte. 0 en la 1ª decisión de Mus; +1 en cada transición
    // descarte→MUS (MusGameLogic). La IA baja su umbral de corte con este
    // contador (AILogic.decideMus) → el bucle de Mus termina. Reset a 0 por
    // reparto (GameState nuevo). Serializable: sobrevive guardar/cargar.
    val musRoundCount: Int = 0,
    // #16 R4.e — Lectura del patrón del rival: por jugador, el envido MÁXIMO
    // que ha lanzado en esta ronda (o 40 si lanzó órdago). Permite a la IA
    // endurecer el umbral de aceptación si un rival ha apostado FUERTE en un
    // lance previo de la ronda (su mano es consistentemente alta → su órdago
    // es real, no farol). Reset por reparto (GameState nuevo, emptyMap por
    // defecto). NO transient: parte del estado autoritativo de la ronda.
    val playerMaxBetThisRound: Map<String, Int> = emptyMap(),
    // Multijugador (host-autoritativo): espejo SERIALIZABLE de [availableActions]
    // para la VISTA que el host publica por asiento. `availableActions` es
    // @Transient (carga texto/iconos R.drawable de UI) → no viaja por la red; el
    // host la proyecta a [GameCommand] y rellena este campo SOLO para el asiento
    // de turno (los demás, lista vacía). Vacío en el juego local, que usa
    // `availableActions` directamente. Ver logic/MatchHost.viewFor.
    val availableCommands: List<GameCommand> = emptyList(),
    // Multijugador: última acción ejecutada, para anunciarla en el cliente
    // ("p3: Paso"). El host la sella por comando; serializable (a diferencia de
    // [lastAction], que es @Transient). null en el juego local. Ver LastActionView.
    val lastActionView: LastActionView? = null
)

@Serializable
data class ScoreDetail(val reason: String, val points: Int)

@Serializable
data class ScoreBreakdown(
    val teamAScoreDetails: List<ScoreDetail> = emptyList(),
    val teamBScoreDetails: List<ScoreDetail> = emptyList()
)

@Serializable
data class OrdagoInfo(val winnerId: String, val lance: GamePhase)

@Serializable
data class ActiveGestureInfo(val playerId: String, val gestureKind: GestureKind)

@Serializable
data class ScoreEventInfo(val teamId: String, val detail: ScoreDetail)

/**
 * Multijugador: la última acción ejecutada en la partida, para que el cliente la
 * ANUNCIE ("p3: Paso"). El host la sella en cada comando ([com.doselfurioso.musvisto.logic.MatchHost.submitCommand]).
 * Lleva el [GameCommand] (serializable), a diferencia de [LastActionInfo] (@Transient).
 */
@Serializable
data class LastActionView(val seatId: String, val command: GameCommand)
