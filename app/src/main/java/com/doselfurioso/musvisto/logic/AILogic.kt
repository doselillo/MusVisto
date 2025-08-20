package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.logic.MusGameLogic
import javax.inject.Inject
import javax.inject.Singleton



// In logic/AILogic.kt

@Singleton
class AILogic @Inject constructor(// 1. INJECT MusGameLogic HERE
    private val gameLogic: MusGameLogic
) {

    private data class HandStrength(
        val grande: Int,  // Score from 0-100 for Grande
        val chica: Int,   // Score from 0-100 for Chica
        val pares: Int,   // Score from 0-100 for Pares
        val juego: Int    // Score from 0-100 for Juego
    )

    fun makeDecision(gameState: GameState, aiPlayer: Player): GameAction {
        val strength = evaluateHand(aiPlayer.hand)

        return when (gameState.gamePhase) {
            GamePhase.MUS_DECISION -> decideMus(strength)
            GamePhase.DISCARD -> decideDiscard(aiPlayer) // Lo mantenemos simple por ahora
            GamePhase.GRANDE -> decideBet(strength.grande)
            GamePhase.CHICA -> decideBet(strength.chica)
            GamePhase.PARES -> decideBet(strength.pares)
            GamePhase.JUEGO -> decideBet(strength.juego)
            else -> GameAction.Paso
        }
    }

    private fun decideMus(strength: HandStrength): GameAction {
        // Si tiene buen Juego o buenos Pares, corta el Mus. Si no, pide Mus.
        return if (strength.juego > 70 || strength.pares > 75) {
            GameAction.NoMus
        } else {
            GameAction.Mus
        }
    }

    private fun decideDiscard(aiPlayer: Player): GameAction {
        // TODO: A future improvement is to make the AI discard its worst cards.
        // For now, it discards its first card.
        return GameAction.ConfirmDiscard
    }

    private fun decideBet(strengthScore: Int): GameAction {
        return when {
            // If the hand is very strong, it bets
            strengthScore > 85 -> GameAction.Envido(2)
            // If the hand is decent, it passes, but might accept a bet later
            strengthScore > 50 -> GameAction.Paso
            // If the hand is weak, it passes
            else -> GameAction.Paso
        }
    }

    private fun evaluateHand(hand: List<Card>): HandStrength {
        // --- GRANDE/CHICA STRENGTH ---
        // We give points for having high cards (Rey=12, Caballo=11...)
        val grandeStrength = (hand.maxOf { it.rank.value } / 12.0 * 100).toInt()
        // For Chica, it's the opposite (As=1 is best)
        val chicaStrength = ((12 - hand.minOf { it.rank.value }) / 11.0 * 100).toInt()

        // --- PARES STRENGTH ---
        val paresPlay = gameLogic.getHandPares(hand) // We reuse our existing logic
        val paresStrength = when (paresPlay) {
            is ParesPlay.Duples -> 90 + (paresPlay.highPair.value) // Duples are very strong
            is ParesPlay.Medias -> 75 + (paresPlay.rank.value)
            is ParesPlay.Pares -> 50 + (paresPlay.rank.value)
            is ParesPlay.NoPares -> 0
        }

        // --- JUEGO STRENGTH ---
        val juegoValue = gameLogic.getHandJuegoValue(hand)
        val juegoStrength = if (juegoValue == 31) 100
        else if (juegoValue == 32) 95
        else if (juegoValue >= 33) (juegoValue / 40.0 * 80).toInt()
        else 0 // No tiene Juego

        return HandStrength(grandeStrength, chicaStrength, paresStrength, juegoStrength)
    }
}