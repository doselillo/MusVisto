package com.doselfurioso.musvisto.logic

import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank


data class AIDecision(val action: GameAction, val cardsToDiscard: Set<Card> = emptySet())

/**
 * Clase AILogic limpia y sin reflexión.
 *
 * Requiere:
 *  - MusGameLogic con getHandPares(hand) y getHandJuegoValue(hand)
 *  - GameState, Player, Card, GameAction, GamePhase, ParesPlay definidos en tu proyecto
 *  - AILogger para logging (puedes pasar null si no quieres logging: ajustar constructor)
 *
 * Si tu GameAction.Discard usa otro formato, adapta el retorno en decideDiscard.
 */
@Singleton
class AILogic @Inject constructor(
    private val gameLogic: MusGameLogic,
    private val logger: AILogger,
    /** Inyectable para tests; por defecto Random.Default */
    private val rng: kotlin.random.Random = kotlin.random.Random.Default
) {

    private data class HandStrength(
        val grande: Int,
        val chica: Int,
        val pares: Int,
        val juego: Int
    )

    // ---------------- Public entry point ----------------
    fun makeDecision(gameState: GameState, aiPlayer: Player): AIDecision {
        val decisionId = UUID.randomUUID().toString()
        val strength = evaluateHand(aiPlayer.hand, aiPlayer, gameState)
        // Log: evaluación inicial
        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "EVALUATE_HAND",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf(
                    "grande" to strength.grande,
                    "chica" to strength.chica,
                    "pares" to strength.pares,
                    "juego" to strength.juego
                ),
                chosenAction = "EVALUATED",
                reason = "Hand evaluated",
                details = emptyMap()
            )
        )

        // Si hay apuesta activa, responder acorde
        gameState.currentBet?.let {
            return AIDecision(decideResponse(strength, gameState, decisionId, aiPlayer))
        }

        // Si no hay apuesta, decidir según la fase
        return when (gameState.gamePhase) {
            GamePhase.MUS -> AIDecision(decideMus(strength, decisionId, aiPlayer))
            GamePhase.DISCARD ->(decideDiscard(aiPlayer, decisionId))
            GamePhase.GRANDE -> AIDecision(decideInitialBet(strength.grande, decisionId, aiPlayer, GamePhase.GRANDE))
            GamePhase.CHICA -> AIDecision(decideInitialBet(strength.chica, decisionId, aiPlayer, GamePhase.CHICA))
            GamePhase.PARES -> AIDecision(decideInitialBet(strength.pares, decisionId, aiPlayer, GamePhase.PARES))
            GamePhase.JUEGO -> AIDecision(decideInitialBet(strength.juego, decisionId, aiPlayer, GamePhase.JUEGO))
            else -> return AIDecision(GameAction.Paso)
        }
    }

    // ---------------- Responder a apuestas activas ----------------
    private fun decideResponse(
        strength: HandStrength,
        gameState: GameState,
        decisionId: String,
        aiPlayer: Player
    ): GameAction {
        val strengthScore = when (gameState.gamePhase) {
            GamePhase.GRANDE -> strength.grande
            GamePhase.CHICA -> strength.chica
            GamePhase.PARES -> strength.pares
            GamePhase.JUEGO -> strength.juego
            else -> 0
        }

        if (gameState.currentBet?.isOrdago == true) {
            Log.d("AILogic", "AI (${aiPlayer.name}) está respondiendo a un ÓRDAGO con una fuerza de $strengthScore.")
            return if (strengthScore >= 95) { // Umbral de certeza muy alto
                GameAction.Quiero
            } else {
                GameAction.NoQuiero
            }
        }

        val currentBetAmount = gameState.currentBet?.amount ?: 0
        if (currentBetAmount >= 4 && strengthScore < 95) {
            // Si la apuesta ya es alta y no tenemos una jugada ganadora, solo aceptamos o rechazamos
            return if (strengthScore-currentBetAmount > 70) GameAction.Quiero else GameAction.NoQuiero
        }

        val action = when {
            strengthScore > 85 -> GameAction.Envido(2)
            strengthScore > 60 -> GameAction.Quiero
            strengthScore > 45 && rng.nextInt(100) < 20 -> GameAction.Quiero
            else -> GameAction.NoQuiero
        }

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "RESPONSE",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("phaseScore" to strengthScore),
                chosenAction = action.toString(),
                reason = "Response decision based on phase score",
                details = mapOf("phase" to gameState.gamePhase.toString())
            )
        )

        return action
    }

    // ---------------- Mus / NoMus ----------------
    private fun decideMus(strength: HandStrength, decisionId: String, aiPlayer: Player): GameAction {
        //val action = if (strength.juego >= 95 || strength.pares >= 65 || (strength.juego + strength.pares) >= 140 || strength.grande >= 95 || strength.chica >= 95) GameAction.NoMus else GameAction.Mus
        val action  = if (strength.juego >= 70 || strength.pares >= 65 || strength.grande >= 85 || strength.chica >= 85) GameAction.NoMus else GameAction.Mus

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "MUS",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("juego" to strength.juego, "pares" to strength.pares),
                chosenAction = action.toString(),
                reason = "Mus decision using thresholds",
                details = mapOf("juego" to strength.juego, "pares" to strength.pares)
            )
        )

        return action
    }

    // ---------------- Descarte (inteligente, protegido contra romper 31) ----------------
    private fun decideDiscard(
        aiPlayer: Player,
        decisionId: String
    ): AIDecision {
        val hand = aiPlayer.hand
        val juegoValue = gameLogic.getHandJuegoValue(hand)

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id,
                phase = "DISCARD",
                hand = hand.map { cardToShortString(it) },
                chosenAction = "EVALUATING_DISCARD",
                reason = "Applying 'Grande' discard strategy."
            )
        )

        // Estrategia Principal:
        // 1. Si se tiene 31 de juego, es una jugada perfecta. No se descarta nada.
        if (juegoValue == 31) {
            // Aunque tenga 31, si hubo Mus, está obligado a descartar.
            // Descartará la carta con el valor más bajo para el juego (un As o un Dos).
            val cardToDiscard = hand.minByOrNull { card ->
                when (card.rank) {
                    Rank.REY, Rank.TRES -> 12
                    else -> card.rank.value
                }
            }
            return AIDecision(GameAction.ConfirmDiscard, setOf(cardToDiscard!!))
        }

        // 2. Estrategia "a la grande": conservar solo Reyes y Treses.
        var cardsToDiscard = hand.filter { it.rank != Rank.REY && it.rank != Rank.TRES }.toSet()

        // 3. REGLA OBLIGATORIA: Si después de aplicar la estrategia no se descarta nada,
        //    forzamos el descarte de la carta menos valiosa de la mano.
        if (cardsToDiscard.isEmpty()) {
            // La mano solo contiene Reyes y Treses. La "peor" es un Tres (valor de rango 3 vs 12 del Rey).
            val worstCard = hand.minByOrNull { it.rank.value }
            if (worstCard != null) {
                cardsToDiscard = setOf(worstCard)
            }
        }

        return AIDecision(GameAction.ConfirmDiscard, cardsToDiscard)
    }

    // ---------------- Apuesta inicial (instrumentado) ----------------
    private fun decideInitialBet(
        strengthScore: Int,
        decisionId: String,
        aiPlayer: Player,
        phase: GamePhase
    ): GameAction {
        val action = when {
            // Umbral subido de 80 a 85
            strengthScore > 85 -> GameAction.Envido(2)
            // Umbral subido de 55 a 60 y probabilidad bajada
            strengthScore > 60 && rng.nextInt(100) < 15 -> GameAction.Envido(2)
            else -> GameAction.Paso
        }

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = phase.toString(),
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("phaseScore" to strengthScore),
                chosenAction = action.toString(),
                reason = "Initial bet evaluated",
                details = mapOf("phaseScore" to strengthScore, "phaseLimit:" to ">85 o >60+15%random")
            )
        )

        return action
    }

    // ---------------- Evaluación de mano ----------------
    private fun evaluateHand(hand: List<Card>, player: Player, gameState: GameState): HandStrength {
        // GRANDE: preferimos 3 y Rey
        val topCardsCount = hand.count { it.rank.value == 12 } // Reyes
        var grandeStrength = when (topCardsCount) {
            0 -> hand.maxOf { it.rank.value } * 4 // Si no hay reyes, puntúa bajo
            1 -> 50 + hand.maxOf { it.rank.value } // Con un rey, es decente
            2 -> 70 + hand.maxOf { it.rank.value } // Con dos, es fuerte
            3 -> 90 // Con tres, es muy fuerte
            4 -> 95 // Con cuatro, es casi seguro ganar
            else -> 0
        }

        // CHICA: Lógica mejorada que valora múltiples cartas bajas
        val lowCardsCount = hand.count { it.rank.value == 1 } // Ases y Doses
        var chicaStrength = when (lowCardsCount) {
            0 -> (12 - hand.minOf { it.rank.value }) * 4 // Sin Ases/Doses, puntúa bajo
            1 -> 60 + (5 - hand.minOf { it.rank.value }) // Con una, es decente
            2 -> 80 // Con dos, es fuerte
            3 -> 95 // Con tres, es muy fuerte
            4 -> 100 // Con cuatro, es imbatible
            else -> 0
        }
        // PARES: reutiliza lógica de gameLogic si existe
        val paresPlay = gameLogic.getHandPares(hand)
        val paresStrength = when (paresPlay) {
            is ParesPlay.Duples -> 100 // Duples es la mejor jugada, siempre debería cortar Mus.
            is ParesPlay.Medias -> (75 + paresPlay.rank.value).coerceAtMost(95) // Muy fuerte, siempre corta.
            is ParesPlay.Pares -> (35 + (paresPlay.rank.value * 2)).coerceAtMost(70) // Puntuación más matizada.
            is ParesPlay.NoPares -> {
                val rankCounts = hand.groupingBy { it.rank.value }.eachCount()
                rankCounts.entries.maxOfOrNull { (rank, cnt) ->
                    if (cnt >= 2 && (rank == 3 || rank == 12)) 40
                    else if (cnt >= 2 && (rank == 1 || rank == 2)) 20
                    else 0
                } ?: 0
            }
        }

        // JUEGO
        val orderedPlayers = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val playerPosition = orderedPlayers.indexOfFirst { it.id == player.id }.takeIf { it != -1 } ?: 0 // 0=mano, 3=postre

        val juegoValue = gameLogic.getHandJuegoValue(hand)
        val baseStrength = when (juegoValue) {
            31 -> 97
            32 -> 70
            40 -> 69
            39 -> 67
            38 -> 65
            37 -> 60
            36 -> 55
            35 -> 50
            34 -> 40
            33 -> 30
            30 -> 60
            29 -> 55
            28 -> 50
            else -> 0
        }

// Ajuste por posición: mano = +3, 2º = +2, 3º = +1, postre = +0
        val juegoStrength = baseStrength + (3 - playerPosition).coerceIn(0, 3)


        return HandStrength(
            grandeStrength.coerceIn(0, 100),
            chicaStrength.coerceIn(0, 100),
            paresStrength.coerceIn(0, 100),
            juegoStrength.coerceIn(0, 100)
        )
    }

    // ---------------- Helpers ----------------
    private fun isTopRank(card: Card): Boolean = card.rank.value == 3 || card.rank.value == 12
    private fun isSecondRank(card: Card): Boolean = card.rank.value == 2 || card.rank.value == 1

    private fun cardKeepPriority(card: Card, hand: List<Card>, isMano: Boolean): Int {
        var p = 0
        when {
            isTopRank(card) -> p += 40
            isSecondRank(card) -> p += 30
            else -> p += 10
        }

        val sameCount = hand.count { it.rank == card.rank }
        if (sameCount >= 2) p += 50

        val valueForJuego = cardJuegoValue(card)
        if (valueForJuego >= 10) p += 20

        if (isMano) p += 10

        return p
    }

    private fun allowDiscardTopRank(hand: List<Card>, isMano: Boolean): Boolean {
        val topCount = hand.count { isTopRank(it) }
        val hasPair = gameLogic.getHandPares(hand) !is ParesPlay.NoPares
        val juego = gameLogic.getHandJuegoValue(hand)
        return !isMano && topCount == 1 && !hasPair && juego < 31
    }

    private fun cardJuegoValue(card: Card): Int = when (card.rank.value) {
        in 10..12 -> 10
        else -> card.rank.value
    }

    private fun cardToShortString(card: Card): String {
        val r = when (card.rank.value) {
            1 -> "A"
            10 -> "10"
            11 -> "11"
            12 -> "R"
            else -> card.rank.value.toString()
        }
        val s = card.suit?.name?.firstOrNull()?.toString() ?: "?"
        return "$r$s"
    }
}
