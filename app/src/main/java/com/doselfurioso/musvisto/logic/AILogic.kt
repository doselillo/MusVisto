package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.debug.DebugFeatures
import java.util.UUID
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import kotlin.math.max

// Apertura de envite del primero: solo abro yo cuando tengo mano ultra-premium
// (>90: 4R/31 mano/duples reyes mano). Con cualquier otra mano apoyo y dejo
// que el capitán decida con su mano + mi seña. Más bajo dejaba al primero
// abrir manos medias-altas y la pareja se "pisaba"; más alto silencia incluso
// los value-bets evidentes de la nuts.
private const val SUPPORT_OWN_FLOOR = 90

// Probabilidad de que un rival INTERCEPTE una seña del equipo contrario.
// Baja: en el Mus real solo se cazan algunas, de vez en cuando. Antes era
// 100% (omnisciencia) -> usar señas era perjudicial. (Backlog #7)
private const val OPPONENT_SIGN_INTERCEPT_PROB = 0.20

// #23: strength efectivo de Grande para duples-de-reyes propio siendo mano.
// >78 ("valor fuerte") pero <90 (no es el value-bet 4-5 más leíble); el
// mus-strategy-reviewer lo fijó en 82.
private const val DUPLES_REY_MANO_GRANDE_STRENGTH = 82

// 1c: umbrales de decisión nombrados (mismos valores que los literales
// previos). Las tablas-`when` de fuerza por jugada se dejan como están: ya
// son auto-documentadas como tabla.
// Corte de Mus: fuerza mínima para NoMus (ajustada por riskFactor y bias).
private const val MUS_CUT_PARES_JUEGO = 75
private const val MUS_CUT_GRANDE_CHICA = 85
// Si el COMPAÑERO es mano, sube el listón de corte (no quitarle la mano).
private const val PARTNER_MANO_MUS_BIAS = 10
// #20 Capitanía delegada de Mus: si actúo ANTES que mi compañero HUMANO y
// VOY A SEÑALIZAR (pendingGestures), delego el corte. Si no señalizo, el
// humano no tendría info → corto por mi mano normal (sin delegar). 5% de
// "break" para no ser absoluto: humano-like, a veces corto aunque toque
// delegar. Solo con compañero humano: el simulador probó que delegar IA→IA
// sangra tantos sin beneficio. Con compañero IA #20 es no-op.
private const val MUS_DELEGATION_BREAK_PCT = 5
// Apertura de envite por bandas: > fuerte = valor seguro; [piso..fuerte] =
// banda media probabilística; < piso = solo farol de robo.
private const val OPEN_STRONG_VALUE = 78
private const val OPEN_MID_BAND_FLOOR = 55

// Penalización al strength del capitán cuando responde a un envite del rival
// y SU compañero (primero del equipo) ya pasó en este lance. Con #20 el
// primero apoya/pasa con manos no ultra-premium → el equipo queda apostando
// SOLO con la mano del capitán; sin esta penalización el capitán acepta como
// si su compañero aportara, y sangra (sim slice: aceptar gana 42% vs 53%
// baseline, neto -738 vs -162). Validado con simulador (200 partidas):
// la magnitud 15 deja aceptar 54.6% wins / -83 tantos netos (incluso mejor
// que baseline). 10 y 12 compensan menos (-225, -142). 15 saca las manos
// strength 75-85 de la banda Quiero — eran las que sangraban.
private const val CAPTAIN_ALONE_RESPONSE_PENALTY = 15

data class AIDecision(
    val action: GameAction,
    val cardsToDiscard: Set<Card> = emptySet(),
    /** Log detallado de la decisión, consumido por el panel de debug en builds debug. */
    val debugLog: String = ""
)

/**
 * Acumulador del log de decisión de la IA. En builds debug se comporta como
 * un StringBuilder; en release es un no-op (no se asigna el StringBuilder ni
 * crecen los appends), evitando el trabajo perdido que señala KNOWN_ISSUES.
 * No afecta a ninguna decisión: el log es puramente informativo.
 */
private class DecisionLog {
    private val sb: StringBuilder? = if (DebugFeatures.IS_ENABLED) StringBuilder() else null
    fun appendLine(line: String) { sb?.appendLine(line) }
    override fun toString(): String = sb?.toString() ?: ""
}

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
        val logBuilder = DecisionLog()

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
        if (playSupport) logBuilder.appendLine(
            "4b. Rol de APOYO: soy el primero del equipo, mano propia floja ($ownBaseLance); cedo al capitán."
        )

        val (decision, actionLog) = if (gameState.currentBet != null) {
            decideBettingResponse(gameState, aiPlayer, finalStrength, playSupport)
        } else {
            decideByPhase(gameState, aiPlayer, finalStrength, riskFactor, playSupport)
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
        if (DebugFeatures.IS_ENABLED) Log.d(TAG, log)
        return decision.copy(debugLog = if (DebugFeatures.IS_ENABLED) log else "")
    }

    /** Responder a una apuesta activa. Devuelve (decisión, línea de log). */
    private fun decideBettingResponse(
        gameState: GameState,
        aiPlayer: Player,
        finalStrength: HandStrength,
        playSupport: Boolean
    ): Pair<AIDecision, String> {
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
        val threshold = 70 // Umbral para "Quiero"
        val actionLog = when {
            playSupport && responseAction !== rawResponse ->
                ">>> FINAL ACTION: Quiero (Apoyo: rebajado desde ${rawResponse.displayText} para no pisar al capitán)"
            responseAction is GameAction.Quiero ->
                ">>> FINAL ACTION: Quiero (Reason: Strength $strengthForLance >= threshold $threshold)"
            else ->
                ">>> FINAL ACTION: ${responseAction.displayText} (Reason: Strength $strengthForLance < threshold $threshold)"
        }
        return AIDecision(responseAction) to actionLog
    }

    /** Decisión sin apuesta activa: MUS / DISCARD / apertura de lance. */
    private fun decideByPhase(
        gameState: GameState,
        aiPlayer: Player,
        finalStrength: HandStrength,
        riskFactor: Int,
        playSupport: Boolean
    ): Pair<AIDecision, String> {
        when (gameState.gamePhase) {
            GamePhase.MUS -> {
                val musResult = decideMus(finalStrength, aiPlayer, riskFactor, gameState)
                return AIDecision(musResult.first) to
                    ">>> FINAL ACTION: ${musResult.first.displayText} (${musResult.second})"
            }
            GamePhase.DISCARD -> {
                val decision = decideDiscard(aiPlayer)
                return decision to
                    ">>> FINAL ACTION: ${decision.action.displayText} (Cards: ${decision.cardsToDiscard.joinToString { cardToShortString(it) }})"
            }
            else -> {
                // Intencional: en APERTURA el `else` cae a juego (la rama
                // else de fase aquí es JUEGO). En decideBettingResponse el
                // `else` es 0 (allí puede haber fases sin lance). NO unificar.
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
                val actionLog = if (playSupport && betAction !== betResult.first) {
                    ">>> FINAL ACTION: Paso (Apoyo: no abro desde mala posición, cedo al capitán; era ${betResult.first.displayText})"
                } else {
                    ">>> FINAL ACTION: ${betResult.first.displayText} (${betResult.second})"
                }
                return AIDecision(betAction) to actionLog
            }
        }
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
        // Compensación #20: si mi compañero (primero del equipo) ya pasó en
        // este lance, el equipo está apostando SOLO con mi mano. Bajo el
        // strength efectivo para que las bandas de aceptación sean más
        // exigentes — rechazo envites del rival con manos medias que antes
        // aceptaba contando con "el aporte" del compañero.
        val effectiveStrength =
            applyCaptainAlonePenalty(adjustedStrength, gameState, aiPlayer)

        // Respuesta a un ÓRDAGO (#28). Antes solo se aceptaba con casi la nuts
        // (adjustedStrength>=95) o perdiendo por >20: un humano spameaba órdago
        // con cualquier mano y la IA plegaba siempre → free-roll explotable.
        // Ahora: umbral por posición (mano gana desempates → acepta más liviano;
        // postre los pierde → exige más), banda muerta para no regalar el chico
        // (#25), zona probabilística estrecha para que el farol puro no sea
        // gratis, y conciencia de marcador simétrica (Hail Mary si el rival
        // está al borde de 40 y no vamos por delante: plegar también pierde).
        if (gameState.currentBet?.isOrdago == true) {
            val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
            val opponentScore = gameState.score[opponentTeam] ?: 0
            val myScore = gameState.score[aiPlayer.team] ?: 0

            // Desesperación / Hail Mary: rechazar también pierde la partida.
            if (opponentScore - myScore > 20) return GameAction.Quiero
            if (opponentScore >= 33 && myScore <= opponentScore) return GameAction.Quiero

            val order = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
            val pos = order.indexOfFirst { it.id == aiPlayer.id }
            val isMano = pos == 0
            val isPostre = pos >= 0 && pos == order.size - 1

            // Umbral base de aceptación (mano legítimamente buena: 31, duples,
            // 3 reyes…), muy por debajo del 95 plano anterior. Ajustado por
            // posición de desempate y por si ganar este lance cierra la partida.
            var acceptThreshold = 84
            if (isMano) acceptThreshold -= 6
            if (isPostre) acceptThreshold += 6
            if (myScore >= 35) acceptThreshold -= 4 // ganarlo cierra: vale el riesgo

            val deadFloor = 75 // por debajo NO se acepta (no regalar el chico, #25)

            return when {
                effectiveStrength >= acceptThreshold -> GameAction.Quiero
                effectiveStrength < deadFloor -> GameAction.NoQuiero
                // Banda media [deadFloor, umbral): llamada probabilística para
                // que spamear órdago con mano floja no sea gratis.
                rng.nextInt(100) < 30 -> GameAction.Quiero
                else -> GameAction.NoQuiero
            }
        }

        // --- LÓGICA DE RESPUESTA MEJORADA ---
        val advantage = effectiveStrength - currentBetAmount
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val opponentScore = gameState.score[opponentTeam] ?: 0

        val action = when {
            // REGLA 1: Solo se plantea un órdago si la ventaja es casi total Y
            // la apuesta ya es alta (más de 10 puntos) O el rival está a punto de ganar.
            advantage > 95 && (currentBetAmount > 10 || opponentScore > 30) -> GameAction.Órdago

            // REGLA 2: Si la ventaja es muy grande (>85), sube la apuesta — cantidad aleatoria 2-4
            // para que la IA sea menos predecible.
            advantage > 85 -> GameAction.Envido(betAmount(effectiveStrength))

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
    /**
     * Resta CAPTAIN_ALONE_RESPONSE_PENALTY al strength si el compañero del
     * jugador ya pasó en este lance — el equipo está apostando solo con su
     * mano. Devuelve el strength efectivo (con suelo en 0).
     */
    private fun applyCaptainAlonePenalty(
        rawStrength: Int,
        gameState: GameState,
        aiPlayer: Player
    ): Int {
        val partner = gameState.players.firstOrNull {
            it.team == aiPlayer.team && it.id != aiPlayer.id
        }
        val captainAlone = partner != null && partner.id in gameState.playersWhoPassed
        return if (captainAlone) {
            (rawStrength - CAPTAIN_ALONE_RESPONSE_PENALTY).coerceAtLeast(0)
        } else rawStrength
    }

    // Capitanía de lance por posición (#1/#4): el primero del equipo cede al
    // capitán (compañero en posición tardía) por norma general. Apoyo si:
    //  1) Mi mano propia no es ULTRA-premium (< SUPPORT_OWN_FLOOR=90): solo
    //     abro yo con 4R/31 mano/duples reyes mano; con cualquier otra mano,
    //     la coordinación con el capitán pesa más que mi value-bet individual.
    //  2) Mi compañero actúa después que yo (es capitán por posición).
    //  3) En lances restringidos (Pares/Juego sin Punto): si el capitán no
    //     participa y yo sí, juego sin freno (modelo: "capitán fuera del lance
    //     + primero sí → primero sin freno").
    //  4) Con compañero HUMANO: solo apoyo si VOY A SEÑALIZAR
    //     (`pendingGestures` contiene mi id). Si no señalizo, el humano juega
    //     sin info y la delegación tira tantos a la basura → juego mi mano.
    //     Coherente con decideMusDelegation: misma puerta para Mus y envites.
    // La seña del compañero NO es requisito: en el modelo el primero es quien
    // señaliza y el segundo quien recibe; exigir `knownGestures[partner.id]`
    // dejaba el apoyo inerte en el caso típico (PR previo, ver KNOWN_ISSUES).
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
        // Capitán = el que actúa más tarde. Si lo soy yo, no apoyo.
        if (myPos > partnerPos) return false
        // Lance restringido (Pares/Juego sin Punto): si el capitán no está en
        // el lance y yo sí, juego sin freno — no cedo a quien no participa.
        val isRestrictedLance = lance == GamePhase.PARES ||
            (lance == GamePhase.JUEGO && !gameState.isPuntoPhase)
        val captainOutOfRestrictedLance = isRestrictedLance &&
            gameState.playersInLance.isNotEmpty() &&
            partner.id !in gameState.playersInLance
        // Gating por seña SOLO con compañero humano (con IA-IA #20 está
        // desactivado y este apoyo es comportamiento-cero respecto a Mus).
        // Si no voy a señalizar, juego mi mano normal: el humano no podrá
        // capitanear con info → mejor que abra/responda yo.
        val humanPartnerWithoutPendingSignal =
            !partner.isAi && aiPlayer.id !in gameState.pendingGestures
        return !captainOutOfRestrictedLance && !humanPartnerWithoutPendingSignal
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
        val manoBias = if (partnerIsMano) PARTNER_MANO_MUS_BIAS else 0

        // Capitanía delegada (#20): si actúo ANTES que mi compañero y este es
        // HUMANO, le delego el corte (lo decide él viendo mi seña). Con
        // compañero IA -> null (comportamiento-cero; ver constantes #20).
        val iActBeforePartner = actsBeforePartner(gameState, aiPlayer, partner)
        val partnerIsAi = partner?.isAi == true
        decideMusDelegation(gameState, aiPlayer, iActBeforePartner, partnerIsAi)?.let { return it }

        val paresCutThreshold = MUS_CUT_PARES_JUEGO - riskFactor + manoBias // Umbral para cortar por pares
        val juegoCutThreshold = MUS_CUT_PARES_JUEGO - riskFactor + manoBias
        val grandeCutThreshold = MUS_CUT_GRANDE_CHICA - riskFactor + manoBias
        val chicaCutThreshold = MUS_CUT_GRANDE_CHICA - riskFactor + manoBias

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

    /** ¿Actúo ANTES que mi compañero en el turno? (capitán = el de después). */
    private fun actsBeforePartner(
        gameState: GameState,
        aiPlayer: Player,
        partner: Player?
    ): Boolean {
        partner ?: return false
        val order = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val myPos = order.indexOfFirst { it.id == aiPlayer.id }
        val partnerPos = order.indexOfFirst { it.id == partner.id }
        return myPos >= 0 && partnerPos >= 0 && myPos < partnerPos
    }

    /**
     * Override de corte por capitanía delegada (#20). Solo actúa si actúo
     * ANTES que mi compañero HUMANO Y voy a señalizar (pendingGestures
     * contiene mi id). Si no señalizo, el humano juega a ciegas → corto por
     * mi mano normal (return null = decideMus sigue con sus umbrales).
     * Con compañero IA → null (no-op: simulador probó EV-negativo).
     * 5% break para variación humana-like (no ser absoluto).
     *
     * TODO #17 (mus corrido): en master no existe ese modo, pero al mergearlo
     * esta delegación DEBE quedar deshabilitada — el mus corrido prohíbe señas
     * y exige decisión de corte individual (determina la mano).
     */
    private fun decideMusDelegation(
        gameState: GameState,
        aiPlayer: Player,
        iActBeforePartner: Boolean,
        partnerIsAi: Boolean
    ): Pair<GameAction, String>? {
        if (!iActBeforePartner || partnerIsAi) return null
        if (aiPlayer.id !in gameState.pendingGestures) return null
        if (rng.nextInt(100) < MUS_DELEGATION_BREAK_PCT) {
            return GameAction.NoMus to "Reason: #20 break ($MUS_DELEGATION_BREAK_PCT%); corto excepcionalmente"
        }
        return GameAction.Mus to "Reason: #20 delego el corte al capitán humano"
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
        // (Antes había una condición 3 "cerrar partida" con strength > 50:
        // regalaba el chico yendo por delante con mano floja —ordagar 36-29 sin
        // nada— porque jugando valor normal se cierra igual sin downside
        // catastrófico. Eliminada (#25). El órdago de cierre legítimo (filo
        // mutuo cerca de 40, casi la nuts) se diseñará junto a #16 como módulo
        // de endgame, no como parche aquí.)

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

        // #23 Parte B: duples-de-reyes propio + ser mano en GRANDE → strength
        // efectivo 82 (cae en "valor fuerte"). Sigue las bandas, NO es un flag
        // determinista: manos de 2 reyes SIN duples siguen entrando por la
        // banda media probabilística y a veces pasan → no telegrafía.
        val ownPares = gameLogic.getHandPares(aiPlayer.hand)
        val duplesDeReyesMano = gameState.gamePhase == GamePhase.GRANDE && isMano &&
            ownPares is ParesPlay.Duples && ownPares.highPair == Rank.REY
        val effStrength = if (duplesDeReyesMano) maxOf(strength, DUPLES_REY_MANO_GRANDE_STRENGTH) else strength

        // 1) Valor fuerte: como siempre.
        if (effStrength > OPEN_STRONG_VALUE) {
            val why = if (duplesDeReyesMano)
                "Valor fuerte ($effStrength) [#23: duples de reyes + mano; Grande base $strength → $effStrength]"
            else "Valor fuerte ($effStrength)"
            return Pair(GameAction.Envido(betAmount(effStrength)), "Reason: $why")
        }

        // 2) Banda media (55-78): valor fino, prob. sube con fuerza y contexto.
        if (effStrength in OPEN_MID_BAND_FLOOR..OPEN_STRONG_VALUE) {
            var p = (effStrength - OPEN_MID_BAND_FLOOR) /
                (OPEN_STRONG_VALUE - OPEN_MID_BAND_FLOOR).toDouble()
            if (isMano) p += 0.15
            if (isLate) p += 0.20
            if (opponentsPassed >= 1) p += 0.15
            p = p.coerceIn(0.0, 0.85) // nunca 100%: no ser leíble
            if (rng.nextDouble() < p) {
                return Pair(GameAction.Envido(betAmount(effStrength)),
                    "Reason: Valor fino media-banda ($effStrength, p=${"%.2f".format(p)})")
            }
        }

        // 3) Farol barato: solo si alguien ya pasó (robo) y marcador no delicado.
        if (effStrength < OPEN_MID_BAND_FLOOR && stealSpot && !scoreRisky) {
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
            // hand no vacía (guard arriba); ?: por robustez ante refactor.
            val cardToDiscard = hand.minByOrNull { baseRank(it.rank) } ?: hand.first()
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
            val worstCard = hand.minByOrNull { cardScores[it] ?: 0 } ?: hand.first()
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
    /**
     * Evalúa la mano en los 4 lances. Orquesta: calcula el contexto de turno
     * una vez y delega cada lance a su función. Comportamiento idéntico al
     * monolito previo (mismas fórmulas y mismas líneas de `explanation`, en
     * el mismo orden Grande→Chica→Pares→Juego).
     *
     * NOTA: la fuerza de Pares NO se consolida con `getParesPlayStrength`
     * a propósito: aquella suma +15 de mano a Duples/Medias (inferencia de
     * seña del compañero), ésta NO (mano propia). Son divergentes por
     * diseño; unificarlas cambiaría el comportamiento.
     */
    private fun evaluateHand(hand: List<Card>, player: Player, gameState: GameState): EvaluationResult {
        if (hand.isEmpty()) return EvaluationResult(HandStrength(0, 0, 0, 0), "   - Empty hand.")

        val explanation = StringBuilder()
        val sortedHand = hand.sortedByDescending { getCardGrandeValue(it) }

        val orderedPlayers = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val playerPositionInTurn = orderedPlayers.indexOfFirst { it.id == player.id }
        val isMano = playerPositionInTurn == 0
        // Rivales (no el compañero) que actúan ANTES que yo en el orden de
        // turno. Clave para el 31: solo se pierde el lance si un RIVAL anterior
        // también tiene 31 (un 31 del compañero por delante gana igual).
        val rivalsAhead = orderedPlayers.take(playerPositionInTurn)
            .count { it.team != player.team }

        val grandeStrength = evaluateGrande(hand, sortedHand, explanation)
        val chicaStrength = evaluateChica(hand, explanation)
        val paresStrength = evaluatePares(hand, isMano, explanation)
        val juegoStrength = evaluateJuego(
            hand, gameState, isMano, playerPositionInTurn, rivalsAhead, explanation
        )

        val finalStrength = HandStrength(
            grande = grandeStrength.coerceIn(0, 100),
            chica = chicaStrength.coerceIn(0, 100),
            pares = paresStrength.coerceIn(0, 100),
            juego = juegoStrength.coerceIn(0, 100)
        )
        return EvaluationResult(finalStrength, explanation.toString())
    }

    private fun evaluateGrande(hand: List<Card>, sortedHand: List<Card>, explanation: StringBuilder): Int {
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
        return grandeStrength
    }

    private fun evaluateChica(hand: List<Card>, explanation: StringBuilder): Int {
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
        // ?: skip del bonus si la mano fuese vacía (no ocurre con el flujo
        // actual); evita el !! frágil sin alterar el cálculo cuando hay carta.
        val bestChicaCard = if (lowCardsCount < 2) hand.minByOrNull { cardChicaOrderValue(it) } else null
        if (bestChicaCard != null) {
            val bonus = ((12 - cardChicaOrderValue(bestChicaCard)) * 1.5).toInt()
            chicaStrength += bonus
            explanation.appendLine("     - Bonus por carta más baja (${cardToShortString(bestChicaCard)}) -> +$bonus pts")
        }
        return chicaStrength
    }

    private fun evaluatePares(hand: List<Card>, isMano: Boolean, explanation: StringBuilder): Int {
        val paresPlay = gameLogic.getHandPares(hand)
        var paresStrength = 0
        explanation.appendLine("   - Pares:")
        when (paresPlay) {
            is ParesPlay.Duples -> {
                paresStrength = duplesStrength(paresPlay)
                explanation.appendLine("     - Duples ${paresPlay.highPair.name}/${paresPlay.lowPair.name} -> $paresStrength pts")
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
                    val manoBonus = manoParesBonus(rankValue.value)
                    paresStrength += manoBonus
                    explanation.appendLine("     - Bonus por ser Mano (escalado #13) -> +$manoBonus pts")
                }
            }

            is ParesPlay.NoPares -> {
                paresStrength = 0; explanation.appendLine("     - No Pares -> 0 pts")
            }
        }
        return paresStrength
    }

    private fun evaluateJuego(
        hand: List<Card>,
        gameState: GameState,
        isMano: Boolean,
        playerPositionInTurn: Int,
        rivalsAhead: Int,
        explanation: StringBuilder
    ): Int {
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

            // El 31 gana a TODO salvo a otro 31 de un RIVAL que actúe antes.
            // Penalización derivada de P(perder) ≈ 1-(1-P_RIVAL_31)^rivalsAhead
            // (no plana por posición: el compañero por delante NO es pérdida).
            // P_RIVAL_31 calibrable. rivalsAhead 1 -> -10, 2 -> -19. #19.
            if (juegoValue == 31 && rivalsAhead > 0) {
                val pRival31 = 0.10
                val pLose = 1.0 - Math.pow(1.0 - pRival31, rivalsAhead.toDouble())
                val posPenalty = (pLose * 100).toInt()
                juegoStrength -= posPenalty
                explanation.appendLine("     - 31 sin ser mano, $rivalsAhead rival(es) delante (P perder ${(pLose * 100).toInt()}%) -> -$posPenalty pts")
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
        return juegoStrength
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

    // ¿Este observador intercepta esta seña rival? Determinista y ESTABLE
    // dentro de la ronda (no parpadea entre lances): hash de mano (rota cada
    // ronda) + emisor + observador + seña. Re-tira solo al cambiar de ronda.
    private fun opponentSignPerceived(
        gameState: GameState,
        observer: Player,
        gesturerId: String,
        gestureResId: Int
    ): Boolean {
        val seed = "${gameState.manoPlayerId}|$gesturerId|${observer.id}|$gestureResId"
        val r = seed.hashCode().mod(1000) / 1000.0
        return r < OPPONENT_SIGN_INTERCEPT_PROB
    }

    private fun adjustStrengthsBasedOnKnownGestures(
        baseStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player,
        logBuilder: DecisionLog
    ): HandStrength {
        if (gameState.knownGestures.isEmpty()) {
            logBuilder.appendLine("2. No gestures remembered this round.")
            return baseStrength
        }

        logBuilder.appendLine("2. Analyzing Remembered Gestures (Team Strength):")
        logBuilder.appendLine("   - Own Base -> G:${baseStrength.grande}, C:${baseStrength.chica}, P:${baseStrength.pares}, J:${baseStrength.juego}")
        // Log de la propia seña: visibilidad de si he señalizado al compañero.
        // Capa importante con delegación amplia (#20): si NO he pasado seña, el
        // capitán humano juega a ciegas y la delegación pierde valor.
        val ownGesture = gameState.knownGestures[aiPlayer.id]
        val ownGestureName = if (ownGesture != null) gestureIdToName(ownGesture.gestureResId) else "(no pasada)"
        logBuilder.appendLine("   - Mi seña activa: $ownGestureName")

        val teamStrength = mergePartnerGestures(baseStrength, gameState, aiPlayer, logBuilder)
        if (teamStrength != baseStrength) {
            logBuilder.appendLine("   -> Consolidated Team Strength -> G:${teamStrength.grande}, C:${teamStrength.chica}, P:${teamStrength.pares}, J:${teamStrength.juego}")
        }

        val finalAdjustedStrength =
            applyOpponentGestures(teamStrength, baseStrength, gameState, aiPlayer, logBuilder)

        if (finalAdjustedStrength != baseStrength) {
            logBuilder.appendLine("   -> Final Adjusted Strength -> G:${finalAdjustedStrength.grande}, C:${finalAdjustedStrength.chica}, P:${finalAdjustedStrength.pares}, J:${finalAdjustedStrength.juego}")
        } else {
            logBuilder.appendLine("   -> Strength not adjusted by gestures.")
        }
        return finalAdjustedStrength
    }

    /** Consolida la fuerza del equipo con las señas conocidas del COMPAÑERO. */
    private fun mergePartnerGestures(
        baseStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player,
        logBuilder: DecisionLog
    ): HandStrength {
        var teamStrength = baseStrength
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
        return teamStrength
    }

    /** Ajusta la fuerza con las señas RIVALES interceptadas (defensivo, #7/#9). */
    private fun applyOpponentGestures(
        teamStrength: HandStrength,
        baseStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player,
        logBuilder: DecisionLog
    ): HandStrength {
        var finalAdjustedStrength = teamStrength
        for ((playerId, gesture) in gameState.knownGestures) {
            val gesturer = gameState.players.find { it.id == playerId } ?: continue
            if (gesturer.team != aiPlayer.team) {
                // El rival NO siempre caza la seña: solo a veces (#7).
                if (!opponentSignPerceived(gameState, aiPlayer, playerId, gesture.gestureResId)) {
                    logBuilder.appendLine("   - (Defensive) Seña de ${gesturer.name} NO interceptada por ${aiPlayer.name}.")
                    continue
                }
                val gestureName = gestureIdToName(gesture.gestureResId)
                logBuilder.appendLine("   - (Defensive) Opponent ${gesturer.name} has '$gestureName' (interceptada).")

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
                        // Intencional: usa el snapshot post-merge (`teamStrength`),
                        // NO `finalAdjustedStrength` (acumulado). Igualaba el
                        // monolito; no cambiar a finalAdjustedStrength.
                        finalAdjustedStrength = finalAdjustedStrength.copy(
                            grande = (teamStrength.grande + 15).coerceIn(0, 100),
                            chica = (teamStrength.chica + 15).coerceIn(0, 100)
                        )
                    }
                    else -> {}
                }

                // #9: una seña rival de reyes/ases/duples/31 también implica
                // fuerza del rival en Grande/Chica, no solo Pares/Juego. Solo
                // bajo mi confianza si esa fuerza implícita SUPERA mi propia
                // mano en ese lance (igual que la rama de Pares): con 4 reyes
                // no me asusta una seña de 3 reyes. Reducción = mitad del boost.
                val oppG = partnerGrandeBoost(gesture.gestureResId)
                val oppC = partnerChicaBoost(gesture.gestureResId)
                if (oppG > baseStrength.grande) {
                    val g = (finalAdjustedStrength.grande - oppG / 2).coerceIn(0, 100)
                    finalAdjustedStrength = finalAdjustedStrength.copy(grande = g)
                    logBuilder.appendLine("     -> Rival más fuerte en Grande (seña $oppG > mi ${baseStrength.grande}): mi Grande -> $g")
                }
                if (oppC > baseStrength.chica) {
                    val c = (finalAdjustedStrength.chica - oppC / 2).coerceIn(0, 100)
                    finalAdjustedStrength = finalAdjustedStrength.copy(chica = c)
                    logBuilder.appendLine("     -> Rival más fuerte en Chica (seña $oppC > mi ${baseStrength.chica}): mi Chica -> $c")
                }
            }
        }
        return finalAdjustedStrength
    }

    // Boost al lance Grande del equipo según la seña del compañero.
    // Estimación moderada: una seña fija 2 cartas pero deja otras 2 desconocidas
    // que podrían rebajar la jugada real para Grande.
    internal fun partnerGrandeBoost(gestureResId: Int): Int = when (gestureResId) {
        R.drawable.reyes_3 -> 90   // 3 Reyes/Treses → dispara Envido seguro (>80)
        R.drawable.sena_31 -> 35   // #14: 31 da figuras pero 0-1 Reyes modal; señal Grande débil
        R.drawable.reyes_2 -> 65   // par alto: activa bluff y empuja a Envido si ya tienes algo
        R.drawable.duples_altos -> 78  // #23: 2 reyes garantizados sin riesgo de descarte; entre reyes_2 y reyes_3
        else -> 0
    }

    // Boost al lance Chica del equipo según la seña del compañero.
    internal fun partnerChicaBoost(gestureResId: Int): Int = when (gestureResId) {
        R.drawable.ases_3 -> 90    // 3 Ases/Doses → dispara Envido seguro
        R.drawable.ases_2 -> 65    // par bajo
        R.drawable.duples_bajos -> 50
        else -> 0
    }

    /**
     * Bonus de fuerza por ser MANO en un PAR ÚNICO, escalado por rango (#13).
     * Ser mano en Pares solo gana empates de mismo rango: en pares bajos
     * (4-7) eso es raro y barato → prima ≈0; en Rey/Tres el empate al tope es
     * frecuente y caro → +15 íntegro (techo intacto, sin regresión). Sustituye
     * al antiguo +15 plano que inflaba pares bajos sobre el umbral de corte de
     * Mus. Curva recomendada por mus-strategy-reviewer. NO aplica a
     * Duples/Medias (fuera del alcance de #13).
     */
    @Suppress("MagicNumber") // Tabla heurística de IA: los valores SON la curva.
    private fun manoParesBonus(pairingRankValue: Int): Int = when {
        pairingRankValue <= 5  -> 0      // As/Dos..Cinco
        pairingRankValue == 6  -> 1      // Seis
        pairingRankValue == 7  -> 2      // Siete
        pairingRankValue <= 10 -> 8      // Sota
        pairingRankValue == 11 -> 11     // Caballo
        else -> 15                       // Rey/Tres
    }

    private fun getParesPlayStrength(paresPlay: ParesPlay, isMano: Boolean): Int {
        var strength = when (paresPlay) {
            is ParesPlay.Duples -> duplesStrength(paresPlay)
            is ParesPlay.Medias -> 60 + (getPairingRankValue(paresPlay.rank) * 2)
            is ParesPlay.Pares -> 20 + (getPairingRankValue(paresPlay.rank) * 3)
            is ParesPlay.NoPares -> 0
        }
        if (isMano) {
            strength += when (paresPlay) {
                // #13: par único escalado por rango; Duples/Medias siguen +15.
                is ParesPlay.Pares -> manoParesBonus(getPairingRankValue(paresPlay.rank))
                is ParesPlay.NoPares -> 0
                else -> 15
            }
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

    // #12: valor de Duples ESCALONADO por rango (antes era 100 plano: unos
    // duples de cincos valían igual que unos de reyes, sobrevalorando duples
    // bajos en Mus/envite/defensa).
    //
    // Duples es categóricamente la mejor jugada de pares, así que su PISO debe
    // quedar por encima del techo de Medias (60 + 12·2 = 84) para que la IA
    // nunca valore unas medias por encima de unos duples. Escalamos en
    // [88..100] ponderando SOBRE TODO el par alto, porque en el Mus los duples
    // se comparan primero por el par alto y luego por el bajo. Duples de reyes
    // = 100; duples bajos = 88.
    //
    // Piso 88 (no 84+1): en `getParesPlayStrength` se suma +15 si isMano antes
    // del coerce, así que una Medias de reyes mano vale 84+15=99; con piso 88
    // unos duples bajos mano valen 100 y mantienen el orden sobre esa Medias
    // (con piso 85 quedaban empatados a 100, ver #13). Span 12 mantiene la
    // compresión deseada (TODOS los duples son premium: ganan el lance de
    // pares salvo duples superiores del rival, raro) sin escalones muertos.
    // Validado con mus-strategy-reviewer; el sim simétrico es ciego a lo que
    // este fix corrige (sobre-apuesta de duples bajos explotable por humano),
    // como en #25 → no bloquear por su net, validar con playtest.
    private fun duplesStrength(duples: ParesPlay.Duples): Int {
        val hv = getPairingRankValue(duples.highPair) // 1..12
        val lv = getPairingRankValue(duples.lowPair)   // 1..12
        val raw = (hv - 1) * 12 + (lv - 1) * 3         // 0..165
        return (88 + raw * 12 / 165).coerceIn(88, 100)
    }
}


