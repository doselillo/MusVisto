package com.doselfurioso.musvisto.logic

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player

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
    fun makeDecision(gameState: GameState, aiPlayer: Player): GameAction {
        val decisionId = UUID.randomUUID().toString()
        val strength = evaluateHand(aiPlayer.hand)

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
            return decideResponse(strength, gameState, decisionId, aiPlayer)
        }

        // Si no hay apuesta, decidir según la fase
        return when (gameState.gamePhase) {
            GamePhase.MUS_DECISION -> decideMus(strength, decisionId, aiPlayer)
            GamePhase.DISCARD -> decideDiscard(aiPlayer, gameState, strength, decisionId)
            GamePhase.GRANDE -> decideInitialBet(strength.grande, decisionId, aiPlayer, GamePhase.GRANDE)
            GamePhase.CHICA -> decideInitialBet(strength.chica, decisionId, aiPlayer, GamePhase.CHICA)
            GamePhase.PARES -> decideInitialBet(strength.pares, decisionId, aiPlayer, GamePhase.PARES)
            GamePhase.JUEGO -> decideInitialBet(strength.juego, decisionId, aiPlayer, GamePhase.JUEGO)
            else -> GameAction.Paso
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
        val action = if (strength.juego >= 95 || strength.pares >= 65 || (strength.juego + strength.pares) >= 130 || strength.grande >= 95 || strength.chica >= 95) GameAction.NoMus else GameAction.Mus

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "MUS_DECISION",
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
        gameState: GameState,
        strength: HandStrength,
        decisionId: String
    ): GameAction {
        val hand = aiPlayer.hand.toMutableList()
        val isMano = (aiPlayer.id == gameState.manoPlayerId)
        val juegoValue = gameLogic.getHandJuegoValue(hand)

        // Protección absoluta: si tienes 31, no descartes nada
        if (juegoValue == 31) {
            logger.log(
                DecisionLog(
                    decisionId = decisionId,
                    timestamp = System.currentTimeMillis(),
                    playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                    phase = "DISCARD",
                    hand = hand.map { cardToShortString(it) },
                    strengths = mapOf("juego" to juegoValue),
                    chosenAction = "NO_DISCARD",
                    reason = "Protección: JUEGO == 31",
                    details = mapOf("juegoValue" to juegoValue)
                )
            )
            return GameAction.ConfirmDiscard
        }

        val paresPlay = gameLogic.getHandPares(hand)

        // Prioridad de conservación (mayor = mejor)
        val priorities = hand.mapIndexed { idx, card ->
            idx to cardKeepPriority(card, hand, isMano)
        }.toMutableList()

        // Heurística para cuántas cartas descartar
        val maxDiscard = 4
        val discardCount = when {
            paresPlay !is ParesPlay.NoPares -> 0
            strength.juego >= 60 || strength.pares >= 60 -> 0
            strength.juego >= 40 || strength.pares >= 40 -> 1
            else -> (2 + rng.nextInt(0, 3)).coerceAtMost(maxDiscard) // 2..4
        }.coerceIn(0, maxDiscard)

        if (discardCount == 0) {
            logger.log(
                DecisionLog(
                    decisionId = decisionId,
                    timestamp = System.currentTimeMillis(),
                    playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                    phase = "DISCARD",
                    hand = hand.map { cardToShortString(it) },
                    strengths = mapOf("grande" to strength.grande, "pares" to strength.pares, "juego" to strength.juego),
                    chosenAction = "NO_DISCARD",
                    reason = "Heurística decidió 0 descartes",
                    details = mapOf("discardCount" to 0)
                )
            )
            return GameAction.ConfirmDiscard
        }

        // Ordenamos por prioridad ascendente (las más bajas son candidatas a descartar)
        priorities.sortBy { it.second }
        val candidateToDiscard = priorities.take(discardCount).map { it.first }.toMutableList()

        // Evitar descartar TopRanks (3 o Rey) salvo condiciones restrictivas
        val hasTopSelected = candidateToDiscard.any { isTopRank(hand[it]) }
        if (hasTopSelected && !allowDiscardTopRank(hand, isMano)) {
            // Reemplazar cada top seleccionado por el siguiente peor no-top si existe
            for (i in candidateToDiscard.indices) {
                val idx = candidateToDiscard[i]
                if (isTopRank(hand[idx])) {
                    val replacement = priorities.firstOrNull {
                        !candidateToDiscard.contains(it.first) && !isTopRank(hand[it.first])
                    }?.first
                    if (replacement != null) candidateToDiscard[i] = replacement
                }
            }
        }

        // Protección adicional: evita romper posibles combos de juego (heurística simple)
        val finalDiscard = candidateToDiscard.filter { idx ->
            val remaining = hand.filterIndexed { i, _ -> i !in candidateToDiscard }
            val newJuego = gameLogic.getHandJuegoValue(remaining)
            // si descartando se destruye un 31 existente, lo evitamos (ya filtrado) — en general evitamos bajar de 31 a <31
            !(juegoValue == 31 && newJuego < 31)
        }

        val reason = "Requested $discardCount, final ${finalDiscard.size}. Priorities: ${
            priorities.joinToString { "(${cardToShortString(hand[it.first])}=${it.second})" }
        }"

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "DISCARD",
                hand = hand.map { cardToShortString(it) },
                strengths = mapOf("grande" to strength.grande, "pares" to strength.pares, "juego" to strength.juego),
                chosenAction = "DISCARD",
                reason = reason,
                details = mapOf("discardIndices" to finalDiscard, "requestedDiscardCount" to discardCount)
            )
        )

        // Devuelve la acción de descarte (adapta si tu GameAction no usa este constructor)
        return GameAction.ConfirmDiscard
    }

    // ---------------- Apuesta inicial (instrumentado) ----------------
    private fun decideInitialBet(
        strengthScore: Int,
        decisionId: String,
        aiPlayer: Player,
        phase: GamePhase
    ): GameAction {
        val action = when {
            strengthScore > 80 -> GameAction.Envido(2)
            strengthScore > 55 && rng.nextInt(100) < 18 -> GameAction.Envido(2)
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
                details = mapOf("phaseScore" to strengthScore)
            )
        )

        return action
    }

    // ---------------- Evaluación de mano ----------------
    private fun evaluateHand(hand: List<Card>): HandStrength {
        // GRANDE: preferimos 3 y Rey
        val maxVal = hand.maxOf { it.rank.value }
        var grandeStrength = (maxVal / 12.0 * 50).toInt()
        if (hand.any { isTopRank(it) }) grandeStrength += 15
       // if (hand.any { isSecondRank(it) }) grandeStrength += 8

        // CHICA: preferimos 2 y As
        val minVal = hand.minOf { it.rank.value }
        var chicaStrength = ((12 - minVal) / 11.0 * 70).toInt()
        if (hand.any { isSecondRank(it) }) chicaStrength += 15
       // if (hand.any { isTopRank(it) }) chicaStrength += 8

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
        val juegoValue = gameLogic.getHandJuegoValue(hand)
        val juegoStrength = when {
            juegoValue == 31 -> 100
            juegoValue == 32 -> rng.nextInt(93, 97)
            juegoValue >= 33 -> (80 + ((juegoValue - 33) * 2)).coerceAtMost(98)
            juegoValue >= 28 -> 70 + ((juegoValue - 28) * 5)
            else -> 0
        }

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
