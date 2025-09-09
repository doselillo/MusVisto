package com.doselfurioso.musvisto.model

// Defines the game phases or "lances".
enum class GamePhase {
    PRE_GAME, MUS, DISCARD, GRANDE, CHICA, PARES_CHECK, PARES, JUEGO_CHECK, JUEGO, ROUND_OVER, GAME_OVER
}

enum class GameEvent {
    DISCARD_PILE_SHUFFLED
}

data class GameState(
    val players: List<Player> = emptyList(),
    val deck: List<Card> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val currentTurnPlayerId: String? = null,
    val gamePhase: GamePhase = GamePhase.PRE_GAME,
    val isPuntoPhase: Boolean = false,
    val score: Map<String, Int> = mapOf("teamA" to 0, "teamB" to 0),
    val availableActions: List<GameAction> = emptyList(),
    val playersWhoPassed: Set<String> = emptySet(),
    val currentBet: BetInfo? = null,
    val agreedBets: Map<GamePhase, Int> = emptyMap(),
    val discardCounts: Map<String, Int> = emptyMap(),
    val selectedCardsForDiscard: Set<Card> = emptySet(),
    val winningTeam: String? = null,
    val manoPlayerId: String = "",
    val lastAction: LastActionInfo? = null,
    val event: GameEvent? = null,
    val betInitiatorTeam: String? = null,
    val playersPendingResponse: List<String> = emptyList(),
    val revealAllHands: Boolean = false,
    val roundHistory: List<LanceResult> = emptyList(),
    val actionLog: List<LastActionInfo> = emptyList(),
    val noMusPlayer: String? = null,
    val isNewLance: Boolean = true,
    val currentLanceActions: Map<String, LastActionInfo> = emptyMap(),
    val transientAction: LastActionInfo? = null,
    val scoreBreakdown: ScoreBreakdown? = null,
    val scoreEvents: List<Pair<String, ScoreDetail>> = emptyList(),
    val ordagoInfo: OrdagoInfo? = null,
    val isSelectingBet: Boolean = false,
    val playersInLance: Set<String> = emptySet(),
    val activeGesture: Pair<String, Int>? = null
)

data class ScoreDetail(val reason: String, val points: Int)
data class ScoreBreakdown(
    val teamAScoreDetails: List<ScoreDetail> = emptyList(),
    val teamBScoreDetails: List<ScoreDetail> = emptyList()
)
data class OrdagoInfo(val winnerId: String, val lance: GamePhase)
