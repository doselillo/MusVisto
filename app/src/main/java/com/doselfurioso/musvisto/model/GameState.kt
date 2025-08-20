package com.doselfurioso.musvisto.model

// Defines the game phases or "lances".
enum class GamePhase {
    PRE_GAME, DEALING, MUS_DECISION, GRANDE, CHICA, PARES, JUEGO, GAME_OVER
}

data class GameState(
    val players: List<Player> = emptyList(),
    val deck: List<Card> = emptyList(),
    val currentTurnPlayerId: String? = null,
    val gamePhase: GamePhase = GamePhase.PRE_GAME,
    val score: Map<String, Int> = mapOf("teamA" to 0, "teamB" to 0),
    // This is the line we added in the last step
    val availableActions: List<GameAction> = emptyList()
)