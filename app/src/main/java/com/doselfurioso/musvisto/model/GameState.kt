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
    val winningTeam: String? = null,
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
    @Transient val transientAction: LastActionInfo? = null,
    val scoreBreakdown: ScoreBreakdown? = null,
    val scoreEvents: List<ScoreEventInfo> = emptyList(),
    val ordagoInfo: OrdagoInfo? = null,
    @Transient val isSelectingBet: Boolean = false,
    val activeGesture: ActiveGestureInfo? = null,
    val knownGestures: Map<String, ActiveGestureInfo> = emptyMap(),
    val playersInLance: Set<String> = emptySet(),
    @Transient val isPaused: Boolean = false
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
