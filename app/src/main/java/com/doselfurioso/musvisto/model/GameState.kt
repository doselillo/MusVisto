package com.doselfurioso.musvisto.model

// Defines the game phases or "lances".
enum class GamePhase {
    PRE_GAME, DEALING, MUS_DECISION, DISCARD, GRANDE, CHICA, PARES, JUEGO, ROUND_OVER, GAME_OVER
}

data class GameState(
    val players: List<Player> = emptyList(),
    val deck: List<Card> = emptyList(),
    val discardPile: List<Card> = emptyList(),
    val currentTurnPlayerId: String? = null,
    val gamePhase: GamePhase = GamePhase.PRE_GAME,
    val isPuntoPhase: Boolean = false,
    val score: Map<String, Int> = mapOf("teamA" to 0, "teamB" to 0),
    // This is the line we added in the last step
    val availableActions: List<GameAction> = emptyList(),
    val playersWhoPassed: Set<String> = emptySet(),
    val currentBet: BetInfo? = null,
    val agreedBets: Map<GamePhase, Int> = emptyMap(),
    val discardCounts: Map<String, Int> = emptyMap(),
    val selectedCardsForDiscard: Set<Card> = emptySet(),
    val winningTeam: String? = null,
    val manoPlayerId: String = "",
    val lastAction: LastActionInfo? = null

)