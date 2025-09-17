package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.R
import java.util.UUID
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import kotlin.math.max


data class AIDecision(val action: GameAction, val cardsToDiscard: Set<Card> = emptySet())

/**
 * Clase AILogic
 *
 * Requiere:
 *  - MusGameLogic con getHandPares(hand) y getHandJuegoValue(hand)
 *  - GameState, Player, Card, GameAction, GamePhase, ParesPlay definidos en tu proyecto
 */
class AILogic constructor(
    private val gameLogic: MusGameLogic,
    /** Inyectable para tests; por defecto Random.Default */
    private val rng: kotlin.random.Random
) {

    private val TAG = "AILogicDebug"

    private data class HandStrength(
        val grande: Int,
        val chica: Int,
        val pares: Int,
        val juego: Int
    )

    private data class EvaluationResult(
        val strength: HandStrength,
        val explanation: String
    )

    // ---------------- Public entry point ----------------
    fun makeDecision(gameState: GameState, aiPlayer: Player): AIDecision {
        val decisionId = UUID.randomUUID().toString().substring(0, 5)
        val logBuilder = StringBuilder()

        logBuilder.appendLine("================ AI DECISION [$decisionId] ================")
        logBuilder.appendLine("Player: ${aiPlayer.name} | Hand: ${aiPlayer.hand.joinToString { cardToShortString(it) }}")
        logBuilder.appendLine("Phase: ${gameState.gamePhase} | Score: Nosotros ${gameState.score["teamA"]} - Ellos ${gameState.score["teamB"]}")
        logBuilder.appendLine("-------------------------------------------------")

        val evaluation = evaluateHand(aiPlayer.hand, aiPlayer, gameState)
        val baseStrength = evaluation.strength
        logBuilder.appendLine("1. Base Hand Strength -> G:${baseStrength.grande}, C:${baseStrength.chica}, P:${baseStrength.pares}, J:${baseStrength.juego}")
        logBuilder.appendLine(evaluation.explanation)

        val gestureAdjustedStrength = adjustStrengthsBasedOnKnownGestures(baseStrength, gameState, aiPlayer, logBuilder)

        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val scoreDifference = (gameState.score[aiPlayer.team] ?: 0) - (gameState.score[opponentTeam] ?: 0)
        val riskFactor = when {
            scoreDifference < -20 -> 15; scoreDifference < -10 -> 10
            scoreDifference > 20 -> -15; scoreDifference > 10 -> -10
            else -> 0
        }
        logBuilder.appendLine("3. Risk Factor: $riskFactor (Score Diff: $scoreDifference)")

        val finalStrength = gestureAdjustedStrength.copy(
            grande = (gestureAdjustedStrength.grande + riskFactor).coerceIn(0, 100),
            chica = (gestureAdjustedStrength.chica + riskFactor).coerceIn(0, 100),
            pares = (gestureAdjustedStrength.pares + riskFactor).coerceIn(0, 100),
            juego = (gestureAdjustedStrength.juego + riskFactor).coerceIn(0, 100)
        )
        logBuilder.appendLine("4. Final Strength -> G:${finalStrength.grande}, C:${finalStrength.chica}, P:${finalStrength.pares}, J:${finalStrength.juego}")
        logBuilder.appendLine("-------------------------------------------------")

        val decision: AIDecision
        val actionLog: String

        if (gameState.currentBet != null) {
            val strengthForLance = when (gameState.gamePhase) {
                GamePhase.GRANDE -> finalStrength.grande
                GamePhase.CHICA -> finalStrength.chica
                GamePhase.PARES -> finalStrength.pares
                GamePhase.JUEGO -> finalStrength.juego
                else -> 0
            }
            val responseAction = decideResponse(strengthForLance, gameState, aiPlayer)
            decision = AIDecision(responseAction)
            // Log para la respuesta
            val threshold = 70 // Umbral para "Quiero"
            actionLog = if (responseAction is GameAction.Quiero) {
                ">>> FINAL ACTION: Quiero (Reason: Strength $strengthForLance >= threshold $threshold)"
            } else {
                ">>> FINAL ACTION: ${responseAction.displayText} (Reason: Strength $strengthForLance < threshold $threshold)"
            }
        } else {
            when (gameState.gamePhase) {
                GamePhase.MUS -> {
                    val musResult = decideMus(finalStrength, aiPlayer, riskFactor)
                    decision = AIDecision(musResult.first)
                    actionLog = ">>> FINAL ACTION: ${musResult.first.displayText} (${musResult.second})"
                }
                GamePhase.DISCARD -> {
                    decision = decideDiscard(aiPlayer)
                    actionLog = ">>> FINAL ACTION: ${decision.action.displayText} (Cards: ${decision.cardsToDiscard.joinToString { cardToShortString(it) }})"
                }
                else -> {
                    val strengthForLance = when(gameState.gamePhase) {
                        GamePhase.GRANDE -> finalStrength.grande
                        GamePhase.CHICA -> finalStrength.chica
                        GamePhase.PARES -> finalStrength.pares
                        else -> finalStrength.juego
                    }
                    val betResult = decideInitialBet(strengthForLance, aiPlayer, gameState)
                    decision = AIDecision(betResult.first)
                    actionLog = ">>> FINAL ACTION: ${betResult.first.displayText} (${betResult.second})"
                }
            }
        }

        logBuilder.appendLine(actionLog)
        logBuilder.appendLine("================ END AI DECISION [$decisionId] ================\n")
        Log.d(TAG, logBuilder.toString())
        return decision
    }

    private fun getPairingRank(rank: Rank): Rank {
        return when (rank) {
            Rank.TRES -> Rank.REY
            Rank.DOS -> Rank.AS
            else -> rank
        }
    }

    private fun getCardGrandeValue(card: Card): Int {
        // Un valor simple de 1 a 10 (As a Rey)
        return when (card.rank) {
            Rank.AS -> 1; Rank.DOS -> 2; Rank.TRES -> 3; Rank.CUATRO -> 4;
            Rank.CINCO -> 5; Rank.SEIS -> 6; Rank.SIETE -> 7;
            Rank.SOTA -> 8; Rank.CABALLO -> 9; Rank.REY -> 10
        }
    }

    private fun getCardChicaValue(card: Card): Int {
        // El inverso de Grande (As=10, Rey=1)
        return 11 - getCardGrandeValue(card)
    }

    private fun cardToShortString(card: Card): String {
        val r = when (card.rank) {
            Rank.AS -> "A"
            Rank.DOS -> "2"
            Rank.TRES -> "3"
            Rank.SOTA -> "S"
            Rank.CABALLO -> "C"
            Rank.REY -> "R"
            else -> card.rank.value.toString()
        }
        val s = card.suit.name.first()
        return "$r$s"
    }

    // ---------------- Responder a apuestas activas ----------------
    private fun decideResponse(
        adjustedStrength: Int,
        gameState: GameState,
        aiPlayer: Player
    ): GameAction {
        val currentBetAmount = gameState.currentBet?.amount ?: 0

        // Lógica para responder a un ÓRDAGO (esta no cambia)
        if (gameState.currentBet?.isOrdago == true) {
            val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
            if (adjustedStrength >= 95 || ((gameState.score[opponentTeam]
                    ?: 0) - (gameState.score[aiPlayer.team] ?: 0) > 20)
            ) {
                return GameAction.Quiero
            } else {
                return GameAction.NoQuiero
            }
        }

        // --- LÓGICA DE RESPUESTA MEJORADA ---
        val advantage = adjustedStrength - currentBetAmount
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val opponentScore = gameState.score[opponentTeam] ?: 0

        val action = when {
            // REGLA 1: Solo se plantea un órdago si la ventaja es casi total Y
            // la apuesta ya es alta (más de 10 puntos) O el rival está a punto de ganar.
            advantage > 95 && (currentBetAmount > 10 || opponentScore > 30) -> GameAction.Órdago

            // REGLA 2: Si la ventaja es muy grande (>85), sube la apuesta, pero de forma comedida.
            advantage > 85 -> GameAction.Envido(2)

            // REGLA 3: El umbral para aceptar un envite ("Quiero") es más exigente.
            advantage > 70 -> GameAction.Quiero

            // REGLA 4: La probabilidad de "pagar por ver" con una mano mediocre es menor.
            advantage > 60 && rng.nextInt(100) < 20 -> GameAction.Quiero // 20% de probabilidad

            else -> GameAction.NoQuiero
        }
        // --- FIN DE LA LÓGICA MEJORADA ---

        return action
    }

    // ---------------- Mus / NoMus ----------------
    private fun decideMus(
        strength: HandStrength,
        aiPlayer: Player,
        riskFactor: Int
    ): Pair<GameAction, String> {
        // --- LÓGICA DE CORTAR MUS MEJORADA ---
        // Ya no se basa en el tipo de jugada, sino en la fuerza calculada.
        val paresCutThreshold = 75 - riskFactor // Umbral para cortar por pares
        val juegoCutThreshold = 75 - riskFactor
        val grandeCutThreshold = 85 - riskFactor
        val chicaCutThreshold = 85 - riskFactor

        if (strength.pares >= paresCutThreshold) return Pair(GameAction.NoMus, "Reason: Pares strength ${strength.pares} >= threshold $paresCutThreshold")
        if (strength.juego >= juegoCutThreshold) return Pair(GameAction.NoMus, "Reason: Juego strength ${strength.juego} >= threshold $juegoCutThreshold")
        if (strength.grande >= grandeCutThreshold) return Pair(GameAction.NoMus, "Reason: Grande strength ${strength.grande} >= threshold $grandeCutThreshold")
        if (strength.chica >= chicaCutThreshold) return Pair(GameAction.NoMus, "Reason: Chica strength ${strength.chica} >= threshold $chicaCutThreshold")

        return Pair(GameAction.Mus, "Reason: No strength exceeds thresholds to cut mus (treshold - riskfactor) Grande/Chica = 85. Pares/Juego = 75")
    }


    private fun decideInitialBet(
        strength: Int,
        aiPlayer: Player,
        gameState: GameState
    ): Pair<GameAction, String> {
        val betThreshold = 80
        val bluffThreshold = 60
        val bluffChance = 5 + (strength / 10)

        if (strength > betThreshold) {
            val amount = rng.nextInt(2, 5)
            return Pair(GameAction.Envido(amount), "Reason: Strength $strength > bet threshold $betThreshold")
        }
        if (strength > bluffThreshold && rng.nextInt(100) < bluffChance) {
            return Pair(GameAction.Envido(2), "Reason: Bluff! Strength $strength > bluff threshold $bluffThreshold (Chance: $bluffChance%)")
        }

        return Pair(GameAction.Paso, "Reason: Strength $strength is below thresholds (Bet > $betThreshold, Bluff > $bluffThreshold)")
    }

    // ---------------- Descarte (inteligente, protegido contra romper 31) ----------------

    fun decideDiscard(
        aiPlayer: Player
    ): AIDecision {
        val hand = aiPlayer.hand

        // Si por alguna razón la mano está vacía, no se descarta nada.
        if (hand.isEmpty()) {
            return AIDecision(GameAction.ConfirmDiscard, emptySet())
        }

        val juegoValue = gameLogic.getHandJuegoValue(hand)

        // REGLA 0: Si tienes 31, estás obligado a descartar una carta,
        // así que tira la que menos valor tenga para la Grande (la de menor rango).
        if (juegoValue == 31) {
            val cardToDiscard = hand.minByOrNull { it.rank.value }!!
            return AIDecision(GameAction.ConfirmDiscard, setOf(cardToDiscard))
        }

        // REGLA 1: SI TIENES 3 FIGURAS (Rey), tienes 30 puntos.
        // Es una jugada muy fuerte para buscar 31 o 40. Descarta la 4ª carta.
        val figures = hand.filter { it.rank.value in 12..12 }
        if (figures.size == 3) {
            val nonFigureCard = hand.first { it !in figures }
            return AIDecision(GameAction.ConfirmDiscard, setOf(nonFigureCard))
        }

        // --- SISTEMA DE PUNTUACIÓN DE CARTAS ---
        val cardScores = mutableMapOf<Card, Int>()
        val rankCounts = hand.groupingBy { it.rank }.eachCount()

        for (card in hand) {
            var score = 0
            if (card.rank == Rank.REY || card.rank == Rank.TRES) score += 50
            when (rankCounts[card.rank] ?: 0) {
                4 -> score += 100
                3 -> score += 80
                2 -> score += 40
            }
            if (card.rank == Rank.AS || card.rank == Rank.DOS) score += 20
            if (card.rank.value in 4..11 && (rankCounts[card.rank] ?: 0) < 2) score -= 10
            cardScores[card] = score
        }

        // Ordenamos las cartas de peor a mejor según su puntuación.
        val sortedCards = hand.sortedBy { cardScores[it] }

        // --- DECISIÓN FINAL DE DESCARTE (CORREGIDA) ---

        // 1. Intentamos descartar todas las cartas que consideramos "malas" (puntuación negativa).
        var cardsToDiscard = sortedCards.filter { (cardScores[it] ?: 0) < 0 }.toSet()

        // 2. REGLA OBLIGATORIA DE MUS: Si la estrategia no ha seleccionado ninguna carta para
        //    descartar (porque la mano es muy buena), FORZAMOS el descarte de la peor carta.
        if (cardsToDiscard.isEmpty()) {
            // 'sortedCards' está ordenada por puntuación de PEOR a MEJOR.
            // La peor carta es la primera de la lista.
            val worstCard = sortedCards.first()
            Log.d("AILogic", "Forcing discard of the worst card: ${cardToShortString(worstCard)}")
            cardsToDiscard = setOf(worstCard)
        }
        // --- FIN DE LA CORRECCIÓN ---

        return AIDecision(GameAction.ConfirmDiscard, cardsToDiscard)
    }


    // ---------------- Evaluación de mano ----------------
    private fun evaluateHand(hand: List<Card>, player: Player, gameState: GameState): EvaluationResult {
        if (hand.isEmpty()) return EvaluationResult(HandStrength(0, 0, 0, 0), "   - Empty hand.")

        val explanation = StringBuilder()
        val sortedHand = hand.sortedByDescending { getCardGrandeValue(it) }

        // --- CÁLCULO DE GRANDE (LÓGICA MEJORADA) ---
        explanation.appendLine("   - Grande:")
        val reyes = hand.filter { it.rank == Rank.REY || it.rank == Rank.TRES }
        val nonReyes = hand.filter { it.rank != Rank.REY && it.rank != Rank.TRES }
            .sortedByDescending { getCardGrandeValue(it) }
        var grandeStrength = 0

        when (reyes.size) {
            4 -> {
                grandeStrength = 100; explanation.appendLine("     - 4 Reyes -> 100 pts (max)")
            }

            3 -> {
                grandeStrength = 90; explanation.appendLine("     - 3 Reyes -> 90 pts")
            }

            2 -> {
                grandeStrength = 65; explanation.appendLine("     - 2 Reyes -> 65 pts")
            }

            1 -> {
                grandeStrength = 40; explanation.appendLine("     - 1 Rey -> 40 pts")
            }

            0 -> {
                val highestCard = sortedHand.first()
                grandeStrength = getCardGrandeValue(highestCard) * 2
                explanation.appendLine(
                    "     - No Reyes, base por carta más alta (${
                        cardToShortString(
                            highestCard
                        )
                    }) -> $grandeStrength pts"
                )
            }
        }
        // Bonus "Kicker" por figuras si ya hay una buena base
        if (reyes.isNotEmpty() && nonReyes.isNotEmpty()) {
            val kicker = nonReyes.first()
            if (kicker.rank == Rank.CABALLO || kicker.rank == Rank.SOTA) {
                val kickerBonus = 5 + getCardGrandeValue(kicker) // Bonus de ~13-14
                grandeStrength += kickerBonus
                explanation.appendLine("     - Kicker Bonus por ${cardToShortString(kicker)} -> +$kickerBonus pts")
            }
        }

        // --- CÁLCULO DE CHICA ---
        val lowCardsCount = hand.count { it.rank.value == 1 } // Ases y Doses
        var chicaStrength = 0
        explanation.appendLine("   - Chica:")
        when (lowCardsCount) {
            4 -> {
                chicaStrength = 100; explanation.appendLine("     - 4 Ases/Doses -> 100 pts (max)")
            }

            3 -> {
                chicaStrength = 90; explanation.appendLine("     - 3 Ases/Doses -> 90 pts")
            }

            2 -> {
                chicaStrength = 65; explanation.appendLine("     - 2 Ases/Doses -> 65 pts")
            }

            1 -> {
                chicaStrength = 40; explanation.appendLine("     - 1 As/Dos -> 40 pts")
            }

            else -> {
                chicaStrength =
                    (12 - hand.minOf { it.rank.value }) * 4; explanation.appendLine("     - No Ases/Doses, base = (12 - ${hand.minOf { it.rank.value }}) * 4 -> $chicaStrength pts")
            }
        }
        if (lowCardsCount < 2) {
            val bonus = ((12 - sortedHand.last().rank.value) * 1.5).toInt()
            chicaStrength += bonus
            explanation.appendLine("     - Bonus por carta más baja (${cardToShortString(sortedHand.last())}) -> +$bonus pts")
        }

        // --- CÁLCULO DE PARES ---
        val paresPlay = gameLogic.getHandPares(hand)
        var paresStrength = 0
        explanation.appendLine("   - Pares:")
        val orderedPlayers = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        // 2. Encontramos la posición del jugador actual EN ESA LISTA (0=mano, 1=segundo, etc.)
        val playerPositionInTurn = orderedPlayers.indexOfFirst { it.id == player.id }
        val isMano = playerPositionInTurn == 0

        when (paresPlay) {
            is ParesPlay.Duples -> {
                paresStrength = 100; explanation.appendLine("     - Duples -> 100 pts (max)")
            }

            is ParesPlay.Medias -> {
                paresStrength = 60 + (getPairingRank(paresPlay.rank).value * 2)
                explanation.appendLine("     - Medias de ${paresPlay.rank.name} -> $paresStrength pts")
            }

            is ParesPlay.Pares -> {
                // El valor del par es ahora el factor principal
                val rankValue = getPairingRank(paresPlay.rank)
                paresStrength = 20 + rankValue.value * 3
                explanation.appendLine("     - Base por Pares de ${paresPlay.rank.name} (valor $rankValue) -> $paresStrength pts")
                if (isMano) {
                    paresStrength += 15
                    explanation.appendLine("     - Bonus por ser Mano -> +15 pts")
                }
            }

            is ParesPlay.NoPares -> {
                paresStrength = 0; explanation.appendLine("     - No Pares -> 0 pts")
            }
        }

        // --- CÁLCULO DE JUEGO ---
        val juegoValue = gameLogic.getHandJuegoValue(hand)
        var juegoStrength = 0
        explanation.appendLine("   - Juego (Valor: $juegoValue, Es Mano: $isMano):")
        explanation.appendLine("   - Juego (Valor: $juegoValue, Posición: ${playerPositionInTurn + 1}):")

        if (juegoValue >= 31) {
            // Lógica para JUEGO (sin cambios)
            val baseJuegoStrength = when (juegoValue) {
                31 -> 95; 32 -> 65; 40 -> 68; 37 -> 60; else -> 50
            }
            juegoStrength = baseJuegoStrength
            explanation.appendLine("     - JUEGO: Base por valor $juegoValue -> $juegoStrength pts")

            if (juegoValue == 31 && !isMano) {
                juegoStrength -= 15
                explanation.appendLine("     - Penalización por tener 31 sin ser mano -> -30 pts")
            }
        } else {
            // --- NUEVA LÓGICA PARA PUNTO ---
            if (gameState.isPuntoPhase) {
                // La fuerza se basa en qué tan cerca está de 30 (la mejor jugada de punto)
                val basePuntoStrength = (juegoValue - 4) * 3 // Mapea el rango 4-30 a una escala de ~0-78
                juegoStrength = basePuntoStrength
                explanation.appendLine("     - PUNTO: Base por valor $juegoValue -> $juegoStrength pts")

                // Bonus muy significativo por ser mano, ya que es la principal ventaja
                if (isMano) {
                    juegoStrength += 20
                    explanation.appendLine("     - Bonus por ser Mano a Punto -> +20 pts")
                }
            } else {
                juegoStrength = 0
                explanation.appendLine("     - No tiene Juego (y no es lance de Punto) -> 0 pts")
            }
        }

        val finalStrength = HandStrength(
            grande = 0, // Placeholder
            chica = 0,  // Placeholder
            pares = 0,  // Placeholder
            juego = juegoStrength.coerceIn(0, 100)
        )

        return EvaluationResult(finalStrength, explanation.toString())
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

    private sealed class GestureMeaning {
        data class Pares(val play: ParesPlay) : GestureMeaning()
        data class Juego(val value: Int) : GestureMeaning()
        object Ciega : GestureMeaning()
    }

    private fun getGestureMeaning(gestureResId: Int): GestureMeaning? {
        return when (gestureResId) {
            // Para las señas de pares, asumimos una jugada representativa fuerte.
            R.drawable.duples_altos -> GestureMeaning.Pares(ParesPlay.Duples(Rank.REY, Rank.REY))
            R.drawable.duples_bajos -> GestureMeaning.Pares(ParesPlay.Duples(Rank.SOTA, Rank.AS))
            R.drawable.reyes_3 -> GestureMeaning.Pares(ParesPlay.Medias(Rank.REY))
            R.drawable.ases_2 -> GestureMeaning.Pares(ParesPlay.Medias(Rank.AS))
            R.drawable.reyes_2 -> GestureMeaning.Pares(ParesPlay.Pares(Rank.REY))
            R.drawable.ases_2 -> GestureMeaning.Pares(ParesPlay.Pares(Rank.AS))

            R.drawable.sena_31 -> GestureMeaning.Juego(31)
            R.drawable.ciega -> GestureMeaning.Ciega

            else -> null
        }
    }


    private fun gestureIdToName(gestureResId: Int): String {
        return when (gestureResId) {
            R.drawable.reyes_2 -> "Dos Reyes"
            R.drawable.reyes_3 -> "Tres Reyes"
            R.drawable.ases_2 -> "Dos Ases"
            R.drawable.ases_3 -> "Tres Ases"
            R.drawable.sena_31 -> "31 de Juego"
            R.drawable.ciega -> "Ciega"
            R.drawable.duples_altos -> "Duples Altos"
            R.drawable.duples_bajos -> "Duples Bajos"
            else -> "Unknown Gesture"
        }
    }

    private fun adjustStrengthsBasedOnKnownGestures(
        baseStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player,
        logBuilder: StringBuilder
    ): HandStrength {
        if (gameState.knownGestures.isEmpty()) {
            logBuilder.appendLine("2. No gestures remembered this round.")
            return baseStrength
        }

        logBuilder.appendLine("2. Analyzing Remembered Gestures (Team Strength):")

        // Empezamos con la fuerza base de la propia IA
        var teamStrength = baseStrength
        logBuilder.appendLine("   - Own Base -> G:${teamStrength.grande}, C:${teamStrength.chica}, P:${teamStrength.pares}, J:${teamStrength.juego}")

        // Primero, consolidamos la fuerza con las señas del compañero
        for ((playerId, gesture) in gameState.knownGestures) {
            val gesturer = gameState.players.find { it.id == playerId } ?: continue
            if (gesturer.team == aiPlayer.team && gesturer.id != aiPlayer.id) {
                val gestureName = gestureIdToName(gesture.gestureResId)
                logBuilder.appendLine("   - (Offensive) Partner ${gesturer.name} has '$gestureName'. Merging strength.")

                // --- LÓGICA DE FUSIÓN CORREGIDA ---
                when (val meaning = getGestureMeaning(gesture.gestureResId)) {
                    is GestureMeaning.Pares -> {
                        val gestureStrength = getParesPlayStrength(meaning.play, false) // Calcula la fuerza de la seña
                        teamStrength = teamStrength.copy(pares = max(teamStrength.pares, gestureStrength))
                        logBuilder.appendLine("     -> Partner Pares Strength is ~$gestureStrength. Team Pares Strength is now ${teamStrength.pares}.")
                    }
                    is GestureMeaning.Juego -> {
                        teamStrength = teamStrength.copy(juego = max(teamStrength.juego, 100))
                        logBuilder.appendLine("     -> Partner Juego Strength is 100. Team Juego Strength is now ${teamStrength.juego}.")
                    }
                    else -> {}
                }
            }
        }

        if(teamStrength != baseStrength){
            logBuilder.appendLine("   -> Consolidated Team Strength -> G:${teamStrength.grande}, C:${teamStrength.chica}, P:${teamStrength.pares}, J:${teamStrength.juego}")
        }


        // Ahora, ajustamos la fuerza del equipo con las señas de los oponentes
        var finalAdjustedStrength = teamStrength
        for ((playerId, gesture) in gameState.knownGestures) {
            val gesturer = gameState.players.find { it.id == playerId } ?: continue
            if (gesturer.team != aiPlayer.team) {
                val gestureName = gestureIdToName(gesture.gestureResId)
                logBuilder.appendLine("   - (Defensive) Opponent ${gesturer.name} has '$gestureName'.")

                when (val meaning = getGestureMeaning(gesture.gestureResId)) {
                    is GestureMeaning.Pares -> {
                        // Solo reduce la fuerza si la seña del rival es mejor que la del equipo
                        if (meaning.play.strength > gameLogic.getHandPares(aiPlayer.hand).strength) {
                            logBuilder.appendLine("     -> Opponent's Pares are stronger. Reducing team Pares confidence to 0.")
                            finalAdjustedStrength = finalAdjustedStrength.copy(pares = 0)
                        }
                    }
                    is GestureMeaning.Juego -> {
                        logBuilder.appendLine("     -> Opponent has Juego. Reducing team Juego confidence to 0.")
                        finalAdjustedStrength = finalAdjustedStrength.copy(juego = 0)
                    }
                    is GestureMeaning.Ciega -> {
                        logBuilder.appendLine("     -> Opponent is weak. Increasing Grande/Chica confidence.")
                        finalAdjustedStrength = finalAdjustedStrength.copy(
                            grande = (teamStrength.grande + 15).coerceIn(0, 100),
                            chica = (teamStrength.chica + 15).coerceIn(0, 100)
                        )
                    }
                    else -> {}
                }
            }
        }

        if (finalAdjustedStrength != baseStrength) {
            logBuilder.appendLine("   -> Final Adjusted Strength -> G:${finalAdjustedStrength.grande}, C:${finalAdjustedStrength.chica}, P:${finalAdjustedStrength.pares}, J:${finalAdjustedStrength.juego}")
        } else {
            logBuilder.appendLine("   -> Strength not adjusted by gestures.")
        }
        return finalAdjustedStrength
    }

    private fun getParesPlayStrength(paresPlay: ParesPlay, isMano: Boolean): Int {
        var strength = when (paresPlay) {
            is ParesPlay.Duples -> 100
            is ParesPlay.Medias -> 60 + (getPairingRankValue(paresPlay.rank) * 2)
            is ParesPlay.Pares -> 20 + (getPairingRankValue(paresPlay.rank) * 3)
            is ParesPlay.NoPares -> 0
        }
        if (isMano && paresPlay !is ParesPlay.NoPares) {
            strength += 15
        }
        return strength.coerceIn(0, 100)
    }

    // --- NUEVA FUNCIÓN HELPER PARA OBTENER EL VALOR DE UN RANGO ---
    private fun getPairingRankValue(rank: Rank): Int {
        return when (rank) {
            Rank.TRES -> 12 // Rey
            Rank.DOS -> 1   // As
            else -> rank.value
        }
    }
}


