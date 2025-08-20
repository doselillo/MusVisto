package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AILogic @Inject constructor() {

    /**
     * The main entry point for the AI.
     * It receives the current game state and the AI player who needs to act,
     * and returns the chosen action.
     */
    fun makeDecision(gameState: GameState, aiPlayer: Player): GameAction {
        // For now, the AI is very simple. We'll add real logic here.
        return when (gameState.gamePhase) {
            GamePhase.MUS_DECISION -> decideMus(gameState, aiPlayer)
            GamePhase.DISCARD -> decideDiscard(gameState, aiPlayer)
            else -> decideBet(gameState, aiPlayer)
        }
    }

    private fun decideMus(gameState: GameState, aiPlayer: Player): GameAction {
        // Simple logic: for now, AI always wants Mus.
        return GameAction.Mus
    }

    private fun decideDiscard(gameState: GameState, aiPlayer: Player): GameAction {
        // Simple logic: for now, AI always discards one card.
        return GameAction.ConfirmDiscard
    }

    private fun decideBet(gameState: GameState, aiPlayer: Player): GameAction {
        // Simple logic: for now, AI always passes.
        return GameAction.Paso
    }
}