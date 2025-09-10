package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.R
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
        // 1. Evaluamos la fuerza base de la mano (como antes)
        val baseStrength = evaluateHand(aiPlayer.hand, aiPlayer, gameState)

        // 2. ¡NUEVO! Ajustamos la fuerza basándonos en las señas que la IA ve.
        val gestureAdjustedStrength =
            adjustStrengthsBasedOnGestures(baseStrength, gameState, aiPlayer)

        // 3. Calculamos el factor de riesgo (como antes)
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val scoreDifference =
            (gameState.score[aiPlayer.team] ?: 0) - (gameState.score[opponentTeam] ?: 0)
        val riskFactor = when {
            scoreDifference < -20 -> 15  // Perdiendo por mucho: muy agresivo
            scoreDifference < -10 -> 10  // Perdiendo: algo agresivo
            scoreDifference > 20 -> -15 // Ganando por mucho: muy conservador
            scoreDifference > 10 -> -10 // Ganando: algo conservador
            else -> 0 // Marcador igualado: juego estándar
        }

        // 4. APLICAMOS EL RISK FACTOR a las fuerzas ya ajustadas por las señas.
        val adjustedGrande = (gestureAdjustedStrength.grande + riskFactor).coerceIn(0, 100)
        val adjustedChica = (gestureAdjustedStrength.chica + riskFactor).coerceIn(0, 100)
        val adjustedPares = (gestureAdjustedStrength.pares + riskFactor).coerceIn(0, 100)
        val adjustedJuego = (gestureAdjustedStrength.juego + riskFactor).coerceIn(0, 100)

        // --- FIN DE LA MODIFICACIÓN ---

        // Log (ahora incluye el risk factor y las fuerzas ajustadas)
        logger.log(
            DecisionLog(
                decisionId = decisionId,
                playerId = aiPlayer.id,
                phase = "EVALUATE_WITH_RISK",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf(
                    "grande" to adjustedGrande, "chica" to adjustedChica,
                    "pares" to adjustedPares, "juego" to adjustedJuego
                ),
                chosenAction = "EVALUATED",
                reason = "Hand evaluated with risk factor",
                details = mapOf("scoreDiff" to scoreDifference, "riskFactor" to riskFactor)
            )
        )

        // --- LÓGICA DE DECISIÓN PRINCIPAL ---
        // Si hay una apuesta activa, la IA responde usando su fuerza ajustada
        gameState.currentBet?.let {
            val strengthForLance = when (gameState.gamePhase) {
                GamePhase.GRANDE -> adjustedGrande
                GamePhase.CHICA -> adjustedChica
                GamePhase.PARES -> adjustedPares
                GamePhase.JUEGO -> adjustedJuego
                else -> 0
            }
            return AIDecision(decideResponse(strengthForLance, gameState, decisionId, aiPlayer))
        }

        // Si no, la IA toma la iniciativa
        return when (gameState.gamePhase) {
            GamePhase.MUS -> AIDecision(
                decideMus(
                    gestureAdjustedStrength,
                    decisionId,
                    aiPlayer,
                    riskFactor
                )
            ) // El Mus se decide con la fuerza base
            GamePhase.DISCARD -> decideDiscard(aiPlayer, decisionId)
            GamePhase.PARES_CHECK -> AIDecision(if (baseStrength.pares > 0) GameAction.Tengo else GameAction.NoTengo)
            GamePhase.JUEGO_CHECK -> AIDecision(if (baseStrength.juego > 0) GameAction.Tengo else GameAction.NoTengo)
            GamePhase.GRANDE -> AIDecision(
                decideInitialBet(
                    adjustedGrande,
                    decisionId,
                    aiPlayer,
                    GamePhase.GRANDE,
                    gameState
                )
            )

            GamePhase.CHICA -> AIDecision(
                decideInitialBet(
                    adjustedChica,
                    decisionId,
                    aiPlayer,
                    GamePhase.CHICA,
                    gameState
                )
            )

            GamePhase.PARES -> AIDecision(
                decideInitialBet(
                    adjustedPares,
                    decisionId,
                    aiPlayer,
                    GamePhase.PARES,
                    gameState
                )
            )

            GamePhase.JUEGO -> AIDecision(
                decideInitialBet(
                    adjustedJuego,
                    decisionId,
                    aiPlayer,
                    GamePhase.JUEGO,
                    gameState
                )
            )

            else -> AIDecision(GameAction.Paso)
        }
    }

    // ---------------- Responder a apuestas activas ----------------
    private fun decideResponse(
        adjustedStrength: Int,
        gameState: GameState,
        decisionId: String,
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

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id,
                phase = "RESPONSE",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("phaseScore" to adjustedStrength, "advantage" to advantage),
                chosenAction = action.toString(),
                reason = "Response decision based on conservative advantage score",
                details = mapOf(
                    "phase" to gameState.gamePhase.toString(),
                    "currentBet" to currentBetAmount
                )
            )
        )

        return action
    }

    // ---------------- Mus / NoMus ----------------
    private fun decideMus(
        baseStrength: HandStrength,
        decisionId: String,
        aiPlayer: Player,
        riskFactor: Int // Recibimos el riskFactor para un pequeño ajuste
    ): GameAction {
        // Si la IA está desesperada (riskFactor alto), será más propensa a cortar el Mus
        // con manos peores, buscando un golpe de suerte.
        val juegoThreshold = 70 - riskFactor
        val paresThreshold = 65 - riskFactor

        val action = if (baseStrength.juego >= juegoThreshold ||
            baseStrength.pares >= paresThreshold ||
            baseStrength.grande >= (85 - riskFactor) ||
            baseStrength.chica >= (85 - riskFactor)
        ) GameAction.NoMus else GameAction.Mus

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = "MUS",
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("juego" to baseStrength.juego, "pares" to baseStrength.pares),
                chosenAction = action.toString(),
                reason = "Mus decision using thresholds",
                details = mapOf("juego" to baseStrength.juego, "pares" to baseStrength.pares)
            )
        )

        return action
    }

    // ---------------- Descarte (inteligente, protegido contra romper 31) ----------------

    fun decideDiscard(
        aiPlayer: Player,
        decisionId: String
    ): AIDecision {
        val hand = aiPlayer.hand
        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id,
                phase = "DISCARD",
                hand = hand.map { cardToShortString(it) },
                chosenAction = "EVALUATING_DISCARD",
                reason = "Applying advanced discard strategy."
            )
        )

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
            if (card.rank.value in 4..7 && (rankCounts[card.rank] ?: 0) < 2) score -= 10
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

    // ---------------- Apuesta inicial (instrumentado) ----------------
    private fun decideInitialBet(
        adjustedStrength: Int,
        decisionId: String,
        aiPlayer: Player,
        phase: GamePhase,
        gameState: GameState // Necesitamos el estado del juego
    ): GameAction {
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val opponentScore = gameState.score[opponentTeam] ?: 0
        if (adjustedStrength > 98 && opponentScore > 30) {
            return GameAction.Órdago
        }
        val action = when {
            adjustedStrength > 90 -> GameAction.Envido(rng.nextInt(3) + 3)
            adjustedStrength > 75 -> GameAction.Envido(2)
            adjustedStrength > 55 && rng.nextInt(100) < 50 -> GameAction.Envido(2) // 50% de probabilidad
            adjustedStrength > 40 && rng.nextInt(100) < (5 + (adjustedStrength / 5)) -> GameAction.Envido(
                2
            ) // Farol más probable si la mano no es malísima
            else -> GameAction.Paso
        }

        logger.log(
            DecisionLog(
                decisionId = decisionId,
                timestamp = System.currentTimeMillis(),
                playerId = aiPlayer.id ?: aiPlayer.name ?: "unknown",
                phase = phase.toString(),
                hand = aiPlayer.hand.map { cardToShortString(it) },
                strengths = mapOf("phaseScore" to adjustedStrength),
                chosenAction = action.toString(),
                reason = "Initial bet evaluated",
                details = mapOf(
                    "phaseScore" to adjustedStrength,
                    "phaseLimit:" to ">85 o >60+15%random"
                )
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
            4 -> 100 // Con cuatro, es casi seguro ganar
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
        val orderedPlayers =
            gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val playerPosition = orderedPlayers.indexOfFirst { it.id == player.id }.takeIf { it != -1 }
            ?: 0 // 0=mano, 3=postre

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

    private fun adjustStrengthsBasedOnGestures(
        baseStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player
    ): HandStrength {
        val activeGesture = gameState.activeGesture ?: return baseStrength
        val gesturerId = activeGesture.playerId
        val gestureResId = activeGesture.gestureResId

        if (gesturerId == aiPlayer.id) return baseStrength

        val gesturer = gameState.players.find { it.id == gesturerId } ?: return baseStrength
        val gestureMeaning = getGestureMeaning(gestureResId) ?: return baseStrength

        var adjustedStrength = baseStrength

        // CASO 1: LA SEÑA ES DE UN COMPAÑERO
        if (gesturer.team == aiPlayer.team) {
            adjustedStrength = when (gestureMeaning) {
                is GestureMeaning.Pares -> baseStrength.copy(pares = 100)
                is GestureMeaning.Juego -> baseStrength.copy(juego = 100)
                is GestureMeaning.Ciega -> baseStrength
            }
            Log.d("AILogic", "IA (${aiPlayer.name}) vio seña del compañero. Confianza AUMENTADA.")
        }
        // CASO 2: LA SEÑA ES DE UN OPONENTE (INTERCEPCIÓN)
        else {
            if (rng.nextFloat() < 0.40f) { // 40% de probabilidad de interceptar
                val myHand = aiPlayer.hand
                val amIMano = gameState.manoPlayerId == aiPlayer.id
                val isOpponentMano = gameState.manoPlayerId == gesturer.id

                when (gestureMeaning) {
                    is GestureMeaning.Juego -> {
                        val myJuegoValue = gameLogic.getHandJuegoValue(myHand)
                        if (gestureMeaning.value == 31) {
                            if (myJuegoValue == 31 && amIMano) {
                                Log.w("AILogic", "IA (${aiPlayer.name}) interceptó 31, pero tiene 31 y es MANO. ¡No se asusta!")
                                adjustedStrength = baseStrength.copy(juego = 100)
                            } else {
                                Log.w("AILogic", "IA (${aiPlayer.name}) interceptó 31 y es VULNERABLE. Confianza REDUCIDA.")
                                adjustedStrength = baseStrength.copy(juego = 0)
                            }
                        }
                    }
                    is GestureMeaning.Pares -> {
                        val myParesPlay = gameLogic.getHandPares(myHand)
                        val opponentParesPlay = gestureMeaning.play
                        if (opponentParesPlay.strength > myParesPlay.strength) {
                            adjustedStrength = baseStrength.copy(pares = 0)
                        } else if (opponentParesPlay.strength == myParesPlay.strength && isOpponentMano) {
                            adjustedStrength = baseStrength.copy(pares = 0)
                        }
                    }
                    // --- INICIO DE LA LÓGICA CORREGIDA ---
                    is GestureMeaning.Ciega -> {
                        // Si el rival va ciego, es una oportunidad para ser más agresivo
                        // en los lances de Grande y Chica, sabiendo que uno de los
                        // oponentes tiene una mano probablemente débil en esos lances.
                        // El bonus es moderado, porque su compañero aún puede tener una buena mano.
                        Log.w("AILogic", "IA (${aiPlayer.name}) interceptó seña de CIEGA. Aumenta confianza en Grande y Chica.")
                        adjustedStrength = baseStrength.copy(
                            grande = (baseStrength.grande + 15).coerceIn(0, 100),
                            chica = (baseStrength.chica + 15).coerceIn(0, 100)
                        )
                    }
                    // --- FIN DE LA LÓGICA CORREGIDA ---
                }
            }
        }
        return adjustedStrength
    }
}
