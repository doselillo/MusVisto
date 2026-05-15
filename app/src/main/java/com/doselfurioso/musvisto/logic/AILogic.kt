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

// Por debajo de esta fuerza propia (pre-fusión con señas) en un lance, la mano
// no es independientemente fuerte: si el compañero señalizó y tiene mejor
// posición, conviene jugar de apoyo en vez de pisarle el envite.
private const val SUPPORT_OWN_FLOOR = 70

data class AIDecision(
    val action: GameAction,
    val cardsToDiscard: Set<Card> = emptySet(),
    /** Log detallado de la decisión, consumido por el panel de debug en builds debug. */
    val debugLog: String = ""
)

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

        val rivalTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val myScore = gameState.score[aiPlayer.team] ?: 0
        val rivalScore = gameState.score[rivalTeam] ?: 0
        val handStr = handToShortString(aiPlayer.hand)

        logBuilder.appendLine("================ AI DECISION [$decisionId] ================")
        logBuilder.appendLine("Player: ${aiPlayer.name} | Mano: $handStr")
        logBuilder.appendLine("Phase: ${gameState.gamePhase} | Marcador: Mi equipo $myScore - Rival $rivalScore")
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
        // Resalta [..*] la cifra del lance que se está decidiendo.
        val activeLance = when (gameState.gamePhase) {
            GamePhase.GRANDE -> "G"; GamePhase.CHICA -> "C"
            GamePhase.PARES -> "P"; GamePhase.JUEGO -> "J"
            else -> ""
        }
        fun lancePart(letter: String, value: Int) =
            if (letter == activeLance) "[$letter:$value*]" else "$letter:$value"
        val finalLine = listOf(
            lancePart("G", finalStrength.grande),
            lancePart("C", finalStrength.chica),
            lancePart("P", finalStrength.pares),
            lancePart("J", finalStrength.juego)
        ).joinToString(" ")
        logBuilder.appendLine("4. Final Strength -> $finalLine")
        logBuilder.appendLine("-------------------------------------------------")

        val decision: AIDecision
        val actionLog: String

        // Capitanía de lance (#1/#4): ¿debo jugar de apoyo y cederle la
        // iniciativa al compañero (mejor posición + seña fuerte conocida)?
        val ownBaseLance = when (gameState.gamePhase) {
            GamePhase.GRANDE -> baseStrength.grande
            GamePhase.CHICA -> baseStrength.chica
            GamePhase.PARES -> baseStrength.pares
            GamePhase.JUEGO -> baseStrength.juego
            else -> 0
        }
        val playSupport = shouldPlaySupport(gameState, aiPlayer, gameState.gamePhase, ownBaseLance)
        if (playSupport) logBuilder.appendLine("4b. Rol de APOYO: compañero capitán de lance (mejor posición + seña fuerte), mano propia floja ($ownBaseLance).")

        if (gameState.currentBet != null) {
            val strengthForLance = when (gameState.gamePhase) {
                GamePhase.GRANDE -> finalStrength.grande
                GamePhase.CHICA -> finalStrength.chica
                GamePhase.PARES -> finalStrength.pares
                GamePhase.JUEGO -> finalStrength.juego
                else -> 0
            }
            val rawResponse = decideResponse(strengthForLance, gameState, aiPlayer)
            // En apoyo no escalo: una subida (Envido/Órdago) se rebaja a Quiero
            // para mantener el bote del equipo sin pisar al capitán.
            val responseAction = if (playSupport &&
                (rawResponse is GameAction.Envido || rawResponse is GameAction.Órdago)
            ) GameAction.Quiero else rawResponse
            decision = AIDecision(responseAction)
            val threshold = 70 // Umbral para "Quiero"
            actionLog = when {
                playSupport && responseAction !== rawResponse ->
                    ">>> FINAL ACTION: Quiero (Apoyo: rebajado desde ${rawResponse.displayText} para no pisar al capitán)"
                responseAction is GameAction.Quiero ->
                    ">>> FINAL ACTION: Quiero (Reason: Strength $strengthForLance >= threshold $threshold)"
                else ->
                    ">>> FINAL ACTION: ${responseAction.displayText} (Reason: Strength $strengthForLance < threshold $threshold)"
            }
        } else {
            when (gameState.gamePhase) {
                GamePhase.MUS -> {
                    val musResult = decideMus(finalStrength, aiPlayer, riskFactor, gameState)
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
                    // En apoyo no abro: dejo hablar a rivales y al capitán.
                    val betAction = if (playSupport &&
                        (betResult.first is GameAction.Envido || betResult.first is GameAction.Órdago)
                    ) GameAction.Paso else betResult.first
                    decision = AIDecision(betAction)
                    actionLog = if (playSupport && betAction !== betResult.first) {
                        ">>> FINAL ACTION: Paso (Apoyo: no abro desde mala posición, cedo al capitán; era ${betResult.first.displayText})"
                    } else {
                        ">>> FINAL ACTION: ${betResult.first.displayText} (${betResult.second})"
                    }
                }
            }
        }

        logBuilder.appendLine(actionLog)
        logBuilder.appendLine("================ END AI DECISION [$decisionId] ================\n")

        // Línea TL;DR al principio: lo esencial de un vistazo. El detalle
        // verboso queda debajo para cuando haga falta profundizar.
        val activeVal = when (gameState.gamePhase) {
            GamePhase.GRANDE -> finalStrength.grande
            GamePhase.CHICA -> finalStrength.chica
            GamePhase.PARES -> finalStrength.pares
            GamePhase.JUEGO -> finalStrength.juego
            else -> null
        }
        val tldr = buildString {
            append("» ${gameState.gamePhase} | $handStr")
            if (activeLance.isNotEmpty() && activeVal != null) append(" | $activeLance:$activeVal")
            if (playSupport) append(" | APOYO")
            append(" -> ${decision.action.displayText}")
        }
        val log = "$tldr\n${logBuilder}"
        Log.d(TAG, log)
        return decision.copy(debugLog = log)
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

    private fun cardChicaOrderValue(card: Card): Int = when (card.rank) {
        Rank.TRES -> 10  // Equivale a Rey: la peor carta para Chica
        Rank.DOS  -> 1   // Equivale a As: la mejor carta para Chica
        else      -> card.rank.value
    }

    // Solo el rango: en Mus el palo no influye en puntuación, pares ni juego.
    private fun cardToShortString(card: Card): String = when (card.rank) {
        Rank.AS -> "A"
        Rank.DOS -> "2"
        Rank.TRES -> "3"
        Rank.SOTA -> "S"
        Rank.CABALLO -> "C"
        Rank.REY -> "R"
        else -> card.rank.value.toString()
    }

    // Mano ordenada de mayor a menor para lectura rápida en el log.
    private fun handToShortString(hand: List<Card>): String =
        hand.sortedByDescending { getCardGrandeValue(it) }
            .joinToString(" ") { cardToShortString(it) }

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

            // REGLA 2: Si la ventaja es muy grande (>85), sube la apuesta — cantidad aleatoria 2-4
            // para que la IA sea menos predecible.
            advantage > 85 -> GameAction.Envido(betAmount(adjustedStrength))

            // REGLA 3: ventaja buena -> casi siempre Quiero, pero NO 100%
            // explotable: cuanto mayor el envite (y la ventaja no aplastante,
            // que ya cae en REGLA 2), alguna probabilidad de no querer. Evita
            // que un rival farmee envidando grande contra, p. ej., un 31 en
            // postre (que pierde el desempate). Los envites pequeños se quieren.
            advantage > 70 -> {
                val foldChance = (((currentBetAmount - 2).coerceAtLeast(0)) * 4)
                    .coerceAtMost(25)
                if (rng.nextInt(100) < foldChance) GameAction.NoQuiero
                else GameAction.Quiero
            }

            // REGLA 4: La probabilidad de "pagar por ver" con una mano mediocre es menor.
            advantage > 60 && rng.nextInt(100) < 20 -> GameAction.Quiero // 20% de probabilidad

            else -> GameAction.NoQuiero
        }
        // --- FIN DE LA LÓGICA MEJORADA ---

        return action
    }

    // ---------------- Mus / NoMus ----------------
    // Capitanía de lance por posición (#1/#4): si el compañero ya señalizó
    // fuerza para este lance y actúa DESPUÉS que yo (mejor posición), y mi mano
    // propia es floja, debo jugar de apoyo: ni abrir ni subir, para no pisarle
    // ni delatar la jugada conocida hablando antes el de mano débil.
    private fun shouldPlaySupport(
        gameState: GameState,
        aiPlayer: Player,
        lance: GamePhase,
        ownBaseLanceStrength: Int
    ): Boolean {
        if (ownBaseLanceStrength >= SUPPORT_OWN_FLOOR) return false
        val partner = gameState.players.firstOrNull {
            it.team == aiPlayer.team && it.id != aiPlayer.id
        } ?: return false
        val order = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val myPos = order.indexOfFirst { it.id == aiPlayer.id }
        val partnerPos = order.indexOfFirst { it.id == partner.id }
        if (myPos < 0 || partnerPos < 0) return false
        // Capitán = el que actúa más tarde (mejor posición). Si lo soy, no apoyo.
        if (myPos > partnerPos) return false
        val partnerGesture = gameState.knownGestures[partner.id] ?: return false
        val resId = partnerGesture.gestureResId
        return when (lance) {
            GamePhase.GRANDE -> partnerGrandeBoost(resId) >= 65
            GamePhase.CHICA -> partnerChicaBoost(resId) >= 65
            GamePhase.PARES -> getGestureMeaning(resId).let {
                it is GestureMeaning.Pares && it.play !is ParesPlay.NoPares
            }
            GamePhase.JUEGO -> getGestureMeaning(resId) is GestureMeaning.Juego
            else -> false
        }
    }

    private fun decideMus(
        strength: HandStrength,
        aiPlayer: Player,
        riskFactor: Int,
        gameState: GameState
    ): Pair<GameAction, String> {
        // --- LÓGICA DE CORTAR MUS MEJORADA ---
        // Ya no se basa en el tipo de jugada, sino en la fuerza calculada.

        // "No quitar mano al compañero": si el compañero es mano, cortar el Mus
        // con una mano solo normalita le roba la opción de mejorar y su ventaja
        // posicional/de desempate. Subimos el listón de corte: solo se corta con
        // mano claramente buena (que supera incluso el umbral elevado).
        val partner = gameState.players.firstOrNull { it.team == aiPlayer.team && it.id != aiPlayer.id }
        val partnerIsMano = partner != null && gameState.manoPlayerId == partner.id
        val manoBias = if (partnerIsMano) 10 else 0

        val paresCutThreshold = 75 - riskFactor + manoBias // Umbral para cortar por pares
        val juegoCutThreshold = 75 - riskFactor + manoBias
        val grandeCutThreshold = 85 - riskFactor + manoBias
        val chicaCutThreshold = 85 - riskFactor + manoBias

        if (strength.pares >= paresCutThreshold) return Pair(GameAction.NoMus, "Reason: Pares strength ${strength.pares} >= threshold $paresCutThreshold (manoBias $manoBias)")
        if (strength.juego >= juegoCutThreshold) return Pair(GameAction.NoMus, "Reason: Juego strength ${strength.juego} >= threshold $juegoCutThreshold (manoBias $manoBias)")
        if (strength.grande >= grandeCutThreshold) return Pair(GameAction.NoMus, "Reason: Grande strength ${strength.grande} >= threshold $grandeCutThreshold (manoBias $manoBias)")
        if (strength.chica >= chicaCutThreshold) return Pair(GameAction.NoMus, "Reason: Chica strength ${strength.chica} >= threshold $chicaCutThreshold (manoBias $manoBias)")

        val musReason = if (partnerIsMano) {
            "Reason: No strength exceeds thresholds; compañero es mano, sesgo a Mus (manoBias +$manoBias)"
        } else {
            "Reason: No strength exceeds thresholds to cut mus"
        }
        return Pair(GameAction.Mus, musReason)
    }


    // Importe de envite sesgado por la fuerza de la jugada: con mano floja
    // (justo sobre el umbral) envida poco; con mano muy fuerte sube más.
    // Mantiene aleatoriedad dentro de cada tramo para no ser predecible.
    private fun betAmount(strength: Int): Int = when {
        strength >= 90 -> rng.nextInt(4, 6)  // 4-5: manos muy fuertes (3+ reyes, 31)
        strength >= 80 -> rng.nextInt(3, 6)  // 3-5
        strength >= 70 -> rng.nextInt(2, 5)  // 2-4
        else -> rng.nextInt(2, 4)            // 2-3: manos justas
    }

    private fun decideInitialBet(
        strength: Int,
        aiPlayer: Player,
        gameState: GameState
    ): Pair<GameAction, String> {
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val myTeamScore = gameState.score[aiPlayer.team] ?: 0
        val opponentScore = gameState.score[opponentTeam] ?: 0
        val scoreDifference = myTeamScore - opponentScore

        // Órdago proactivo en escenarios marcados:
        // 1) Desesperación: vamos perdiendo y el rival está cerca de cantar 40. Mano buena.
        if (strength > 75 && scoreDifference < -15 && opponentScore > 25) {
            return Pair(GameAction.Órdago, "Reason: Desperation (diff $scoreDifference, opp $opponentScore, strength $strength)")
        }
        // 2) Bloquear victoria rival: el rival está a un envite de ganar y tenemos mano muy fuerte.
        if (opponentScore >= 30 && strength > 80 && scoreDifference < -10) {
            return Pair(GameAction.Órdago, "Reason: Block opponent win (opp $opponentScore, diff $scoreDifference, strength $strength)")
        }
        // 3) Cerrar partida: estamos a un envite de ganar nosotros con mano decente.
        if (myTeamScore >= 35 && opponentScore < 35 && strength > 50) {
            return Pair(GameAction.Órdago, "Reason: Closing the game (my $myTeamScore, opp $opponentScore, strength $strength)")
        }

        // --- APERTURA POR BANDAS CON CONTEXTO (farol / valor controlado) ---
        // Antes solo abría con casi la nuts (>80): los lances morían a paso.
        // Ahora: valor fuerte siempre; valor fino de manos medias con prob.
        // según contexto posicional; farol barato solo en spots de robo.
        val order = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val myPos = order.indexOfFirst { it.id == aiPlayer.id }
        val isMano = myPos == 0
        val isLate = myPos >= 0 && myPos >= order.size - 2 // postre o penúltimo
        // Rivales que YA han pasado este lance => iniciativa más libre (robo).
        val opponentsPassed = gameState.playersWhoPassed.count { pid ->
            gameState.players.find { it.id == pid }?.team != aiPlayer.team &&
                gameState.players.any { it.id == pid }
        }
        val stealSpot = opponentsPassed >= 1
        val scoreRisky = opponentScore >= 33 || myTeamScore >= 33

        // 1) Valor fuerte: como siempre.
        if (strength > 78) {
            return Pair(GameAction.Envido(betAmount(strength)),
                "Reason: Valor fuerte ($strength)")
        }

        // 2) Banda media (55-78): valor fino, prob. sube con fuerza y contexto.
        if (strength in 55..78) {
            var p = (strength - 55) / 23.0
            if (isMano) p += 0.15
            if (isLate) p += 0.20
            if (opponentsPassed >= 1) p += 0.15
            p = p.coerceIn(0.0, 0.85) // nunca 100%: no ser leíble
            if (rng.nextDouble() < p) {
                return Pair(GameAction.Envido(betAmount(strength)),
                    "Reason: Valor fino media-banda ($strength, p=${"%.2f".format(p)})")
            }
        }

        // 3) Farol barato: solo si alguien ya pasó (robo) y marcador no delicado.
        if (strength < 55 && stealSpot && !scoreRisky) {
            val bluffP = (0.10 + if (isLate) 0.10 else 0.0).coerceAtMost(0.20)
            if (rng.nextDouble() < bluffP) {
                return Pair(GameAction.Envido(2),
                    "Reason: Farol de robo ($strength, p=${"%.2f".format(bluffP)})")
            }
        }

        return Pair(GameAction.Paso, "Reason: No abre (strength $strength, robo=$stealSpot)")
    }

    // ---------------- Descarte (heurística por scoring de carta) ----------------
    //
    // Cada carta recibe un score = baseRank + groupBonus + juegoBonus + duplesBonus - deadPenalty.
    // Se descartan todas las cartas con score < DISCARD_THRESHOLD.
    // Reglas duras antes del scoring para los casos con jugada clara (31, 3 figuras).

    private val figureRanks = setOf(Rank.REY, Rank.TRES, Rank.CABALLO, Rank.SOTA)
    private val deadRange = setOf(Rank.CUATRO, Rank.CINCO, Rank.SEIS, Rank.SIETE)
    private val discardThreshold = 25
    private val duplesBonus = 30
    private val deadPenalty = 25

    fun decideDiscard(aiPlayer: Player): AIDecision {
        val hand = aiPlayer.hand

        // Defensivo: si la mano está vacía no hay nada que descartar.
        if (hand.isEmpty()) {
            return AIDecision(GameAction.ConfirmDiscard, emptySet())
        }

        val juegoValue = gameLogic.getHandJuegoValue(hand)

        // Regla dura: 31 obliga a descartar — tira la de menor baseRank para
        // preservar Grande (Rey/Tres) y Chica (As/Dos).
        if (juegoValue == 31) {
            val cardToDiscard = hand.minByOrNull { baseRank(it.rank) }!!
            return AIDecision(GameAction.ConfirmDiscard, setOf(cardToDiscard))
        }

        // Regla dura: 3 figuras + 1 no-figura → tira la no-figura buscando el 31.
        val figures = hand.filter { it.rank in figureRanks }
        if (figures.size == 3) {
            val nonFigureCard = hand.first { it !in figures }
            return AIDecision(GameAction.ConfirmDiscard, setOf(nonFigureCard))
        }

        val pairingCounts = hand.groupingBy { getPairingRank(it.rank) }.eachCount()
        val hasDuples = pairingCounts.values.count { it == 2 } == 2
        val cardScores = hand.associateWith { card ->
            scoreCard(card, pairingCounts, juegoValue, hasDuples)
        }

        var cardsToDiscard = hand.filter { (cardScores[it] ?: 0) < discardThreshold }.toSet()

        // Mus obligatorio: si nada baja del umbral (mano excelente), descarta la peor.
        if (cardsToDiscard.isEmpty()) {
            val worstCard = hand.minByOrNull { cardScores[it] ?: 0 }!!
            Log.d(TAG, "Mus obligatorio: descartando la peor carta: ${cardToShortString(worstCard)}")
            cardsToDiscard = setOf(worstCard)
        }

        // TODO Mano-aware: cuando aiPlayer es el "mano" del turno, el scoring
        // debería ser más exigente con figuras no-Rey (Caballo/Sota) y premiar
        // más Grande/Pares/Juego — ser mano gana los desempates, así que las
        // jugadas medias rinden más. Requiere pasar gameState.manoPlayerId aquí.

        return AIDecision(GameAction.ConfirmDiscard, cardsToDiscard)
    }

    private fun scoreCard(
        card: Card,
        pairingCounts: Map<Rank, Int>,
        juegoValue: Int,
        hasDuples: Boolean
    ): Int {
        val pairingRank = getPairingRank(card.rank)
        val groupSize = pairingCounts[pairingRank] ?: 1
        val isPaired = groupSize >= 2

        val base = baseRank(card.rank)
        val group = groupBonus(groupSize, pairingRank)
        val juego = juegoBonus(juegoValueOf(card.rank), juegoValue)
        val duples = if (hasDuples) duplesBonus else 0
        val dead = if (card.rank in deadRange && !isPaired && juegoValue < 27) deadPenalty else 0

        return base + group + juego + duples - dead
    }

    private fun baseRank(rank: Rank): Int = when (rank) {
        Rank.REY, Rank.TRES -> 30
        Rank.AS, Rank.DOS -> 20
        Rank.CABALLO -> 10
        Rank.SOTA -> 8
        Rank.SIETE -> 6
        Rank.SEIS -> 5
        Rank.CINCO -> 4
        Rank.CUATRO -> 3
    }

    private fun groupBonus(size: Int, pairingRank: Rank): Int = when (size) {
        4 -> 60
        3 -> when (pairingRank) {
            Rank.REY, Rank.AS -> 70
            Rank.CABALLO, Rank.SOTA -> 50
            else -> 40
        }
        2 -> when (pairingRank) {
            Rank.REY -> 30
            Rank.AS, Rank.CABALLO, Rank.SOTA -> 20
            else -> 5
        }
        else -> 0
    }

    private fun juegoBonus(cardJuegoValue: Int, totalJuego: Int): Int = when {
        totalJuego == 31 -> if (cardJuegoValue == 10) 25 else 10
        totalJuego in 32..40 -> if (cardJuegoValue == 10) 5 else 0
        else -> 0
    }

    private fun juegoValueOf(rank: Rank): Int = when (rank) {
        Rank.SOTA, Rank.CABALLO, Rank.REY, Rank.TRES -> 10
        Rank.DOS -> 1
        else -> rank.value
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
        val lowCardsCount = hand.count { it.rank == Rank.AS || it.rank == Rank.DOS }
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
                val minOrderValue = hand.minOf { cardChicaOrderValue(it) }
                chicaStrength = (12 - minOrderValue) * 4
                explanation.appendLine("     - No Ases/Doses, base = (12 - $minOrderValue) * 4 -> $chicaStrength pts")
            }
        }
        if (lowCardsCount < 2) {
            val bestChicaCard = hand.minByOrNull { cardChicaOrderValue(it) }!!
            val bonus = ((12 - cardChicaOrderValue(bestChicaCard)) * 1.5).toInt()
            chicaStrength += bonus
            explanation.appendLine("     - Bonus por carta más baja (${cardToShortString(bestChicaCard)}) -> +$bonus pts")
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
        explanation.appendLine("   - Juego (Valor: $juegoValue, Posición: ${playerPositionInTurn + 1}, Es Mano: $isMano):")

        if (juegoValue >= 31) {
            // El 31 es premium (además suma +1 tanto por "juego de 31"). El resto
            // baja con un salto claro: un 32 es decente pero NO está cerca del 31.
            val baseJuegoStrength = when (juegoValue) {
                31 -> 95  // Mejor juego, con diferencia
                32 -> 70  // Segundo, pero lejos del 31
                40 -> 60
                37 -> 52
                36 -> 48
                35 -> 44
                34 -> 41
                else -> 38  // 33, el peor juego
            }
            juegoStrength = baseJuegoStrength
            explanation.appendLine("     - JUEGO: Base por valor $juegoValue -> $juegoStrength pts")

            // El 31 gana a TODO salvo a otro 31 más cercano a mano. Penaliza
            // por posición (suave: el único riesgo es "otro 31"), no plano:
            // un 31 en postre puede perder ante 31 de pos 1, 2 ó 3.
            if (juegoValue == 31 && playerPositionInTurn > 0) {
                val posPenalty = playerPositionInTurn * 5
                juegoStrength -= posPenalty
                explanation.appendLine("     - 31 sin ser mano, posición ${playerPositionInTurn + 1} -> -$posPenalty pts")
            }

            // Los empates de Juego los gana quien está más cerca de mano. Con un
            // juego no-31 (empatable a menudo), cuanto más tarde se actúa peor:
            // un 32 en postre pierde el desempate. Penaliza por posición.
            if (juegoValue in 32..40 && playerPositionInTurn > 0) {
                val posPenalty = playerPositionInTurn * 6
                juegoStrength -= posPenalty
                explanation.appendLine("     - Penalización por posición ${playerPositionInTurn + 1} con juego no-31 -> -$posPenalty pts")
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
            grande = grandeStrength.coerceIn(0, 100),
            chica = chicaStrength.coerceIn(0, 100),
            pares = paresStrength.coerceIn(0, 100),
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
            R.drawable.ases_3 -> GestureMeaning.Pares(ParesPlay.Medias(Rank.AS))
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
                val gesturerIsMano = gameState.manoPlayerId == gesturer.id

                // Cada seña del compañero implica info parcial sobre Grande y Chica además
                // de la jugada concreta. Aplicamos un boost moderado al lance que cuadra:
                // las 2 cartas que no vemos podrían tirar la jugada del compañero, por eso
                // no asumimos el máximo.
                val grandeBoost = partnerGrandeBoost(gesture.gestureResId)
                val chicaBoost = partnerChicaBoost(gesture.gestureResId)
                if (grandeBoost > teamStrength.grande) {
                    teamStrength = teamStrength.copy(grande = grandeBoost)
                    logBuilder.appendLine("     -> Partner gesture boosts Grande to $grandeBoost.")
                }
                if (chicaBoost > teamStrength.chica) {
                    teamStrength = teamStrength.copy(chica = chicaBoost)
                    logBuilder.appendLine("     -> Partner gesture boosts Chica to $chicaBoost.")
                }

                when (val meaning = getGestureMeaning(gesture.gestureResId)) {
                    is GestureMeaning.Pares -> {
                        val gestureStrength = getParesPlayStrength(meaning.play, gesturerIsMano)
                        teamStrength = teamStrength.copy(pares = max(teamStrength.pares, gestureStrength))
                        logBuilder.appendLine("     -> Partner Pares Strength is ~$gestureStrength (mano=$gesturerIsMano). Team Pares Strength is now ${teamStrength.pares}.")
                    }
                    is GestureMeaning.Juego -> {
                        // 31 sin ser mano puede perder ante otro 31 más cerca de mano:
                        // mismo trato que para la propia mano en evaluateHand.
                        val juegoStrength = if (gesturerIsMano) 100 else 85
                        teamStrength = teamStrength.copy(juego = max(teamStrength.juego, juegoStrength))
                        logBuilder.appendLine("     -> Partner Juego Strength is $juegoStrength (mano=$gesturerIsMano). Team Juego Strength is now ${teamStrength.juego}.")
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

    // Boost al lance Grande del equipo según la seña del compañero.
    // Estimación moderada: una seña fija 2 cartas pero deja otras 2 desconocidas
    // que podrían rebajar la jugada real para Grande.
    internal fun partnerGrandeBoost(gestureResId: Int): Int = when (gestureResId) {
        R.drawable.reyes_3 -> 90   // 3 Reyes/Treses → dispara Envido seguro (>80)
        R.drawable.sena_31 -> 70   // 31 implica figuras pero no necesariamente Reyes
        R.drawable.reyes_2 -> 65   // par alto: activa bluff y empuja a Envido si ya tienes algo
        R.drawable.duples_altos -> 50  // info parcial: por sí sola no dispara, pero suma con la propia
        else -> 0
    }

    // Boost al lance Chica del equipo según la seña del compañero.
    internal fun partnerChicaBoost(gestureResId: Int): Int = when (gestureResId) {
        R.drawable.ases_3 -> 90    // 3 Ases/Doses → dispara Envido seguro
        R.drawable.ases_2 -> 65    // par bajo
        R.drawable.duples_bajos -> 50
        else -> 0
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


