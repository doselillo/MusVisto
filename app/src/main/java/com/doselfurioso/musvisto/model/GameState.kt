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
    // Pre-decisión por IA: si va a pasar seña en este Mus, qué seña (resId).
    // null/ausente = no pasa. Permite que AILogic y triggerAiGestures
    // (corrutinas separadas) decidan coherentemente: si NO voy a señalizar,
    // no delego el corte ni apoyo envites — mi compañero humano no tendrá
    // info, así que juego mi mano normal. Se asigna al entrar a MUS y se
    // limpia al salir.
    val pendingGestures: Map<String, Int> = emptyMap(),
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
    val musRoundCount: Int = 0
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
data class ActiveGestureInfo(val playerId: String, val gestureResId: Int)

@Serializable
data class ScoreEventInfo(val teamId: String, val detail: ScoreDetail)
