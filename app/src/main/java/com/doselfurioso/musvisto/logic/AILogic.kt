package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.debug.DebugFeatures
import java.util.UUID
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.GestureKind
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import kotlin.math.max

// Las palancas de comportamiento de la IA viven ahora en [AIProfile] (cada
// instancia de AILogic recibe un perfil → los rivales dejan de ser clones, #34).
// Dentro de la clase se exponen como `val` de instancia con los MISMOS nombres
// que las antiguas constantes, así que los cuerpos de los métodos no cambian.
// La documentación/rationale de cada palanca está junto a su campo en AIProfile.

// Probabilidad de que mi juego AGUANTE frente a UN rival por delante (modelo de
// showdown de juego: 1 - 0.90^nRivales = prob. de que al menos uno me gane). No
// es palanca de personalidad: es una constante del modelo de fuerza, no del perfil.
private const val JUEGO_HOLD_PROB_PER_RIVAL = 0.90

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
// Las palancas de personalidad se exponen como `val` de instancia conservando los
// nombres UPPER_SNAKE de las antiguas constantes (traza 1:1 con AIProfile y los
// métodos no cambian). Son constantes de configuración por instancia → se silencia
// VariableNaming a nivel de clase.
@Suppress("VariableNaming")
class AILogic constructor(
    private val gameLogic: MusGameLogic,
    /** Inyectable para tests; por defecto Random.Default */
    private val rng: kotlin.random.Random,
    /**
     * Personalidad de esta IA (#34). Por defecto = baseline ya validado, así que
     * `AILogic(gameLogic, rng)` reproduce la IA de producción bit a bit. Cada
     * rival recibe el suyo (ver GameViewModel) para dejar de ser clones.
     */
    private val profile: AIProfile = AIProfile()
) {

    private val TAG = "AILogicDebug"

    // Palancas de personalidad resueltas del perfil. Conservan los nombres de
    // las antiguas constantes a nivel de fichero → los métodos no se tocan.
    // Rationale de cada una: junto a su campo en AIProfile.
    private val SUPPORT_OWN_FLOOR = profile.supportOwnFloor
    private val OPPONENT_SIGN_INTERCEPT_PROB = profile.opponentSignInterceptProb
    private val DUPLES_REY_MANO_GRANDE_STRENGTH = profile.duplesReyManoGrandeStrength
    private val MUS_CUT_PARES_JUEGO = profile.musCutParesJuego
    private val MUS_CUT_GRANDE_CHICA = profile.musCutGrandeChica
    private val PARTNER_MANO_MUS_BIAS = profile.partnerManoMusBias
    private val MUS_FATIGUE_STEP = profile.musFatigueStep
    private val MUS_DELEGATION_BREAK_PCT = profile.musDelegationBreakPct
    private val OPEN_STRONG_VALUE = profile.openStrongValue
    private val OPEN_MID_BAND_FLOOR = profile.openMidBandFloor
    private val CAPTAIN_ALONE_RESPONSE_PENALTY = profile.captainAloneResponsePenalty
    private val PENDING_LANCE_LOSS_THRESHOLD = profile.pendingLanceLossThreshold
    private val ENDGAME_ORDAGO_TIGHT_FLOOR = profile.endgameOrdagoTightFloor
    private val ENDGAME_ORDAGO_LAST_LANCE_FLOOR = profile.endgameOrdagoLastLanceFloor
    private val Q2_MAX_DIFF = profile.q2MaxDiff
    private val ENDGAME_ORDAGO_HAILMARY_FLOOR = profile.endgameOrdagoHailmaryFloor
    private val R1A_AMPLE_DIFF = profile.r1aAmpleDiff
    private val ENDGAME_ORDAGO_HAILMARY_LOOSE_FLOOR = profile.endgameOrdagoHailmaryLooseFloor
    private val ENDGAME_CATASTROPHE_DIFF = profile.endgameCatastropheDiff
    private val ENDGAME_ORDAGO_CATASTROPHE_FLOOR = profile.endgameOrdagoCatastropheFloor
    private val OPPONENT_LOOSE_DISCARD_MIN = profile.opponentLooseDiscardMin
    private val ENDGAME_ORDAGO_PAIR_HINT_BONUS = profile.endgameOrdagoPairHintBonus
    private val ENDGAME_BORDER_SCORE = profile.endgameBorderScore
    private val ENDGAME_TIGHT_DIFF = profile.endgameTightDiff
    private val ENDGAME_STANDARD_CLOSE_BET = profile.endgameStandardCloseBet
    private val ENDGAME_REMAINING_LANCE_FLOOR = profile.endgameRemainingLanceFloor
    private val R1B_OPPONENT_WIN_THRESHOLD = profile.r1bOpponentWinThreshold
    private val R1B_MY_HAND_FLOOR = profile.r1bMyHandFloor
    private val ORDAGO_RESPONSE_PARTNER_HINT_BONUS = profile.ordagoResponsePartnerHintBonus
    private val ORDAGO_RESPONSE_OPP_STRONG_BET_PENALTY = profile.ordagoResponseOppStrongBetPenalty
    private val STRONG_BET_THRESHOLD = profile.strongBetThreshold
    // (ordagoResponseTightEndgameBonus quedó muerto al retirar R4.f — sigue en
    //  AIProfile documentado por si R4.f vuelve, pero no se expone aquí.)

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
        val (rawResponse, responseReason) = decideResponse(strengthForLance, finalStrength, gameState, aiPlayer)
        // En apoyo no escalo: una subida (Envido/Órdago) se rebaja a Quiero
        // para mantener el bote del equipo sin pisar al capitán.
        val responseAction = if (playSupport &&
            (rawResponse is GameAction.Envido || rawResponse is GameAction.Órdago)
        ) GameAction.Quiero else rawResponse
        // El umbral de aceptación es CONTEXTUAL (órdago vs envite, posición,
        // marcador, foldChance…). decideResponse devuelve la razón real para
        // que el log no afirme un umbral fijo inexistente.
        val actionLog = if (playSupport && responseAction !== rawResponse)
            ">>> FINAL ACTION: Quiero (Apoyo: rebajado desde ${rawResponse.displayText} " +
                "para no pisar al capitán; base: $responseReason)"
        else
            ">>> FINAL ACTION: ${responseAction.displayText} (Reason: $responseReason)"
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
                val betResult = decideInitialBet(strengthForLance, finalStrength, aiPlayer, gameState)
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
    // Complejidad pre-existente de un método de decisión central; reducirla es una
    // tarea aparte con validación de simulador (el comportamiento lo fija el snapshot).
    @Suppress("CyclomaticComplexMethod")
    private fun decideResponse(
        adjustedStrength: Int,
        finalStrength: HandStrength,
        gameState: GameState,
        aiPlayer: Player
    ): Pair<GameAction, String> {
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
        // gratis, y Hail-Mary GATEADO: solo se acepta a ciegas si rechazar
        // entrega la partida EN EL ACTO (opp + pointsIfRejected >= 40, #33).
        if (gameState.currentBet?.isOrdago == true) {
            val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
            val opponentScore = gameState.score[opponentTeam] ?: 0
            val myScore = gameState.score[aiPlayer.team] ?: 0

            // Hail-Mary REAL: rechazar el órdago entrega la partida EN EL ACTO.
            // Dos vías de "rechazar pierde ya":
            //  (1) la no querida del órdago: handleNoQuiero suma pointsIfRejected
            //      al rival al instante (MusGameLogic).
            //  (2) los envites YA QUERIDOS pendientes de showdown en OTROS lances
            //      (agreedBets), que se cobran al cierre: si los que voy perdiendo
            //      bastan para llevar al rival a 40, rechazar también pierde —y
            //      aceptar este órdago CORTA la ronda, cancelándolos (#33 follow-up)—.
            // Solo entonces vale jugarse este lance a ciegas; si no, rechazar
            // conserva la varianza de los lances que quedan. (Antes dos overrides
            // ciegos aceptaban por el mero hecho de ir detrás → -EV explotable.)
            val pointsIfRejected = gameState.currentBet?.pointsIfRejected ?: 1
            // Pendientes que probablemente PIERDO: mi fuerza (ya ajustada por señas
            // del compañero) está por debajo del umbral en ese lance. Sesgo SEGURO
            // (subcontar): no inflo el marcador del rival con lances que en realidad
            // gano, así no acepto órdagos perdidos teniendo ganancias pendientes.
            // Nunca mira las cartas del rival (#7).
            val pendingRivalPoints = gameState.agreedBets.entries.sumOf { (lance, amount) ->
                val myStrength = when (lance) {
                    GamePhase.GRANDE -> finalStrength.grande
                    GamePhase.CHICA -> finalStrength.chica
                    GamePhase.PARES -> finalStrength.pares
                    GamePhase.JUEGO -> finalStrength.juego
                    else -> 100
                }
                if (lance != gameState.gamePhase && myStrength < PENDING_LANCE_LOSS_THRESHOLD) amount else 0
            }
            if (opponentScore + pointsIfRejected + pendingRivalPoints >= 40) return GameAction.Quiero to
                "Órdago: Quiero forzado — rechazar entrega la partida (opp $opponentScore + " +
                    "noQuerida $pointsIfRejected + pendientes $pendingRivalPoints >= 40)"

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
            // (R4.d sinergia con seña) Seña FUERTE del compañero (duples,
            // reyes 3/2, 31, 30 de juego). El órdago all-in compara TODOS los
            // lances al showdown, no sólo el actual. `finalStrength` ya
            // incluye un boost por la seña, pero SÓLO sobre el lance concreto
            // de la seña (mergePartnerGestures). En lances distintos, esta
            // rebaja explícita captura que la pareja gana al menos un lance
            // garantizado y eleva la probabilidad de supervivencia conjunta.
            if (partnerHasStrongSignal(gameState, aiPlayer)) {
                acceptThreshold -= ORDAGO_RESPONSE_PARTNER_HINT_BONUS
            }
            // (R4.e — lectura del patrón del rival, 2026-05-28) Si algún
            // rival ha lanzado un envido FUERTE (>= STRONG_BET_THRESHOLD) o
            // un órdago en un LANCE PREVIO de esta ronda, su mano es
            // consistentemente alta → este órdago es real, no farol →
            // endurecer el umbral (+5). NO incluye el envido del lance
            // actual (ese es el órdago al que respondo).
            if (opponentBetStrongInPriorLance(gameState, aiPlayer)) {
                acceptThreshold += ORDAGO_RESPONSE_OPP_STRONG_BET_PENALTY
            }
            // (R4.f eliminada 2026-05-28) El bonus -3 en endgame ajustado
            // contribuía al sangrado del aceptar-net en sim simétrico
            // (-61990 vs baseline -18005): la IA aceptaba demasiados órdagos
            // basura confiando en el timing del cobro. Si el playtest pide
            // más agresividad al aceptar en endgame, restaurar con
            // calibración fina (instrumentar accept-net por zona de score).

            val deadFloor = 75 // por debajo NO se acepta (no regalar el chico, #25)

            return when {
                effectiveStrength >= acceptThreshold ->
                    GameAction.Quiero to "Órdago: Quiero (effStrength $effectiveStrength >= umbral $acceptThreshold)"
                effectiveStrength < deadFloor ->
                    GameAction.NoQuiero to "Órdago: NoQuiero (effStrength $effectiveStrength < piso $deadFloor)"
                // Banda media [deadFloor, umbral): llamada probabilística para
                // que spamear órdago con mano floja no sea gratis.
                rng.nextInt(100) < 30 ->
                    GameAction.Quiero to "Órdago: Quiero (banda media [$deadFloor,$acceptThreshold), llamada 30%)"
                else ->
                    GameAction.NoQuiero to "Órdago: NoQuiero (banda media [$deadFloor,$acceptThreshold), sin llamada)"
            }
        }

        // --- LÓGICA DE RESPUESTA MEJORADA ---
        val advantage = effectiveStrength - currentBetAmount
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val opponentScore = gameState.score[opponentTeam] ?: 0

        val (action, reason) = when {
            // REGLA 1: Solo se plantea un órdago si la ventaja es casi total Y
            // la apuesta ya es alta (más de 10 puntos) O el rival está a punto de ganar.
            advantage > 95 && (currentBetAmount > 10 || opponentScore > 30) ->
                GameAction.Órdago to "REGLA1: Órdago (ventaja $advantage > 95, " +
                    "envite $currentBetAmount / opp $opponentScore)"

            // REGLA 2: Si la ventaja es muy grande (>85), sube la apuesta — cantidad aleatoria 2-4
            // para que la IA sea menos predecible.
            advantage > 85 ->
                GameAction.Envido(betAmount(effectiveStrength, isRaise = true)) to
                    "REGLA2: subo el envite (ventaja $advantage > 85)"

            // REGLA 3: ventaja buena -> casi siempre Quiero, pero NO 100%
            // explotable: cuanto mayor el envite (y la ventaja no aplastante,
            // que ya cae en REGLA 2), alguna probabilidad de no querer. Evita
            // que un rival farmee envidando grande contra, p. ej., un 31 en
            // postre (que pierde el desempate). Los envites pequeños se quieren.
            //
            // Curva *6 cap 80 (era *4 cap 25): el simulador a 50.000p (a/b
            // limpio) mostró que la cap del 25% dejaba aceptar envites 4-13
            // con win-rate 15-25% — sangraba estructuralmente. Subir a *6/80
            // pliega envites grandes mucho más sin tocar el caso común
            // (envite=2 sigue con foldChance=0%). Mejora: aceptar deficitario
            // −21.390 → −13.819 sobre 50.000p (+35%).
            //
            // Override #19 PARTE B: para 31-en-JUEGO no-mano con rivalsAhead,
            // el foldChance se deriva de P(pierdo) en vez de la curva por
            // tamaño. Antes con envite=2 (foldChance=0% en la curva) el 31
            // expuesto en postre se quería SIEMPRE y perdía a otro 31 mano.
            //
            // ANTI-SOBRE-PLEGADO (umbral 70→60; REGLA 4 60→50): el oponente
            // asimétrico del simulador (Fase 3) mostró que la IA PIERDE el
            // matchup contra un rival que farolea/abre flojo (spammer 45%,
            // loose 48%) porque, calibrada contra apuestas genuinas (sim
            // simétrico), pliega manos medias que ganarían al farol. Bajar el
            // umbral a 60 paga envites pequeños con par de reyes / duples-reyes
            // y arregla el matchup (spammer 55%, loose 57%) sin tocar
            // station/órdago (otro código). Coste: el aceptar-neto del sim
            // SIMÉTRICO sangra más (−1525→−4209/2000p) — punto ciego conocido
            // (vs apostante genuino aceptar siempre sangra; el beneficio real
            // es vs humano que farolea). El arnés aislado lo confirma causal:
            // regret 2.34→1.96, acierto EV 59%→66%. La rodilla está en 60: a
            // 55 sangra más por ganancia marginal.
            //
            // Se probó hacerlo POSITION-AWARE (60 ∓6 mano/postre, y postre-only
            // +6) por recomendación del mus-strategy-reviewer: el arnés y el
            // matchup salían IGUALES o LIGERAMENTE PEORES (el plano está en la
            // rodilla y captura mejor el value ex-post). Se mantiene plano.
            // (Backlog #1, lado respuesta.)
            advantage > 60 -> {
                val baseFold = (((currentBetAmount - 2).coerceAtLeast(0)) * 6)
                    .coerceAtMost(80)
                val foldChance = juego31LossOverride(gameState, aiPlayer) ?: baseFold
                if (rng.nextInt(100) < foldChance)
                    GameAction.NoQuiero to "REGLA3: NoQuiero (ventaja $advantage > 60, foldChance $foldChance%)"
                else
                    GameAction.Quiero to "REGLA3: Quiero (ventaja $advantage > 60, foldChance $foldChance%)"
            }

            // REGLA 4: "pagar por ver" con mano media-floja. 5% -> 10%
            // (decisión del usuario 2026-05-22, sensación > EV anti-timidez).
            // Historia: se bajó 20% -> 5% tras simulador 50.000p (a/b) porque
            // la franja advantage 60-70 sale a favor con frecuencia
            // insuficiente para compensar (manos no-31 y 32-40 no-mano) y el
            // "pagar por ver" sangra sobre todo en GRANDE y PARES; la curva
            // 20→10→5 daba retornos disminuyentes (-4.426 → -1.805 tantos
            // extra) y a 5% el aceptar quedaba en break-even (-0.09 t/partida).
            // El simulador a 5% seguía avisando "IA demasiado tímida (acepta
            // ~6%)" y el usuario lo confirma por intuición de playtest, así que
            // se sube a 10% asumiendo el coste de EV (estimado ~+1.800 tantos
            // de sangrado sobre 50.000p, a confirmar con el sim) a cambio de
            // que la IA dispute más lances. Si vuelve a sentirse tímida, la
            // siguiente palanca NO es esta (retorno disminuyente) sino la
            // agresividad de APERTURA del capitán (ver backlog #20/#11).
            advantage > 50 && rng.nextInt(100) < 10 ->
                GameAction.Quiero to "REGLA4: Quiero pagar-por-ver (ventaja $advantage > 50, 10%)"

            else -> GameAction.NoQuiero to "Sin ventaja suficiente: NoQuiero (ventaja $advantage)"
        }
        // --- FIN DE LA LÓGICA MEJORADA ---

        return action to reason
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

    /**
     * Override del foldChance de REGLA 3 cuando el aceptante tiene **31 a
     * Juego, no es mano, y hay rivales que actúan antes** (#19 PARTE B).
     *
     * Problema que ataca: la curva por tamaño daba foldChance=0% con
     * envite=2, así que un 31 expuesto en postre se quería SIEMPRE y a
     * veces perdía a otro 31 mano. El simulador a 50.000p mostraba JUEGO
     * sangrando −22.830 tantos netos (61% del sangrado total de aceptar).
     *
     * Modelo: foldChance = P(pierdo) · 100, con
     * P(pierdo) ≈ 1 − (1 − P_RIVAL_31)^rivalsAhead (mismo modelo que la
     * penalización en `evaluateJuego`). Modulación por marcador:
     *  - "arriesgoMás" (voy por delante y rival lejos): foldChance −8.
     *  - "prudente" (voy por detrás o rival al borde de 40): foldChance +12.
     *
     * Devuelve null si no es el caso (REGLA 3 normal aplica).
     */
    private fun juego31LossOverride(gameState: GameState, aiPlayer: Player): Int? {
        if (gameState.gamePhase != GamePhase.JUEGO) return null
        if (gameLogic.getHandJuegoValue(aiPlayer.hand) != 31) return null
        val order = gameLogic.getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val pos = order.indexOfFirst { it.id == aiPlayer.id }
        if (pos <= 0) return null  // mano: gana desempates, no necesita override
        val rivalsAhead = order.take(pos).count { it.team != aiPlayer.team }
        if (rivalsAhead <= 0) return null  // solo compañero delante: no es pérdida

        val pRival31 = 0.10
        val pLose = 1.0 - Math.pow(1.0 - pRival31, rivalsAhead.toDouble())
        var foldChance = (pLose * 100).toInt()

        // Modulación por marcador (#19 PARTE B pieza 1).
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val myScore = gameState.score[aiPlayer.team] ?: 0
        val opponentScore = gameState.score[opponentTeam] ?: 0
        val scoreDiff = myScore - opponentScore
        when {
            scoreDiff >= 0 && opponentScore < 30 ->
                foldChance = (foldChance - 8).coerceAtLeast(0)
            scoreDiff < 0 || opponentScore >= 33 ->
                foldChance = (foldChance + 12).coerceAtMost(80)
        }
        return foldChance
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

        // #37 Fatiga de Mus: baja el umbral de corte con cada ciclo Mus+descarte
        // ya jugado en esta ronda (0 en la 1ª decisión). Garantiza que el bucle
        // de Mus de la IA termina; ramp suave para no matar el Mus legítimo.
        val fatigue = gameState.musRoundCount * MUS_FATIGUE_STEP

        val paresCutThreshold = MUS_CUT_PARES_JUEGO - riskFactor + manoBias - fatigue // Umbral para cortar por pares
        val juegoCutThreshold = MUS_CUT_PARES_JUEGO - riskFactor + manoBias - fatigue
        val grandeCutThreshold = MUS_CUT_GRANDE_CHICA - riskFactor + manoBias - fatigue
        val chicaCutThreshold = MUS_CUT_GRANDE_CHICA - riskFactor + manoBias - fatigue

        // ¿Mi mano MERECE cortarse por sí sola? (razón, o null si no supera ningún
        // umbral). Se calcula ANTES de la delegación para gatear el break: el
        // primero solo corta por iniciativa con una mano cortable, nunca al azar.
        val biasInfo = "manoBias $manoBias, fatiga $fatigue (mus #${gameState.musRoundCount})"
        val cutReason = when {
            strength.pares >= paresCutThreshold ->
                "Pares strength ${strength.pares} >= threshold $paresCutThreshold ($biasInfo)"
            strength.juego >= juegoCutThreshold ->
                "Juego strength ${strength.juego} >= threshold $juegoCutThreshold ($biasInfo)"
            strength.grande >= grandeCutThreshold ->
                "Grande strength ${strength.grande} >= threshold $grandeCutThreshold ($biasInfo)"
            strength.chica >= chicaCutThreshold ->
                "Chica strength ${strength.chica} >= threshold $chicaCutThreshold ($biasInfo)"
            else -> null
        }

        // Capitanía delegada (#20): si actúo ANTES que mi compañero y voy a
        // señalizar (pendingGestures), le delego el corte (decide él con mi seña
        // + su mano). El break (corte por iniciativa) SOLO aplica si mi mano
        // merece cortarse — no un corte aleatorio con cualquier mano. Aplica a
        // partner humano Y partner IA — la restricción a humano-only sangraba
        // sensación de pareja IA↔IA en playtest (el primero IA cortaba con buena
        // mano cuando debería ceder al segundo).
        val iActBeforePartner = actsBeforePartner(gameState, aiPlayer, partner)
        decideMusDelegation(gameState, aiPlayer, iActBeforePartner, cutReason != null)?.let { return it }

        cutReason?.let { return GameAction.NoMus to "Reason: $it" }

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
     * Override de corte por capitanía delegada (#20). Actúa si:
     *  - Actúo ANTES que mi compañero (posicionalmente soy primero del equipo).
     *  - Voy a señalizar (mi id en `pendingGestures`). Si no señalizo, el
     *    capitán juega a ciegas → corto por mis umbrales normales.
     *
     * Aplica a compañero humano Y compañero IA. La versión inicial (PR #47)
     * acotaba a humano-only por el resultado del simulador, pero el playtest
     * en pareja IA↔IA reveló que el primero seguía cortando con buenas manos
     * que debería ceder. Por decisión de producto se restablece la simetría
     * humano/IA aunque el simulador detecte sangrado — la sensación de
     * pareja real prevalece y la compensación va por otro lado
     * (`CAPTAIN_ALONE_RESPONSE_PENALTY` ya está activa).
     *
     * Break = el primero toma la INICIATIVA de cortar su BUENA mano en vez de
     * delegar, ocasionalmente (variación humana-like, no ser absoluto). SOLO si
     * `handWouldCut` (la mano supera un umbral de corte): un break con mano floja
     * era un corte sin sentido (sensación de IA tonta en playtest, espíritu del
     * #13). Con mano no-cortable SIEMPRE delega (pide Mus) y señaliza: el capitán
     * decide con la seña + su mano.
     *
     * #17 (mus corrido): deshabilitada mientras el modo está activo — el mus
     * corrido prohíbe señas y exige corte individual (determina la mano). El
     * guard de abajo es defensa explícita; además `onEnterMusPhase` no rellena
     * `pendingGestures` en mus corrido, así que el gate de señas ya falla solo.
     */
    private fun decideMusDelegation(
        gameState: GameState,
        aiPlayer: Player,
        iActBeforePartner: Boolean,
        handWouldCut: Boolean
    ): Pair<GameAction, String>? {
        if (gameState.musCorrido) return null
        if (!iActBeforePartner) return null
        if (aiPlayer.id !in gameState.pendingGestures) return null
        if (handWouldCut && rng.nextInt(100) < MUS_DELEGATION_BREAK_PCT) {
            return GameAction.NoMus to
                "Reason: #20 break ($MUS_DELEGATION_BREAK_PCT%); corto mi buena mano por iniciativa"
        }
        return GameAction.Mus to "Reason: #20 delego el corte al capitán (humano o IA)"
    }


    // Importe de envite. 90% mínimo (2 si apertura, 1 si subida): es el default
    // real del Mus — las subidas son recurso ocasional, no la norma, y subir
    // sobre un envite previo a +1 es el incremento mínimo realista. El 10%
    // restante sí sube y sigue sesgado por fuerza — una subida a 4-5 sigue
    // siendo indicador de mano fuerte, pero rara. Calibración por playtest (#11).
    private fun betAmount(strength: Int, isRaise: Boolean = false): Int {
        if (rng.nextInt(100) < 90) return if (isRaise) 1 else 2
        return when {
            strength >= 90 -> rng.nextInt(4, 6)  // 4-5: manos muy fuertes (3+ reyes, 31)
            strength >= 80 -> rng.nextInt(3, 6)  // 3-5
            strength >= 70 -> rng.nextInt(3, 5)  // 3-4
            else -> 3
        }
    }

    /**
     * El COMPAÑERO ha señalizado una mano FUERTE conocida: duples, medias o
     * pares de reyes, o 31 de juego. La pareja gana el showdown del all-in
     * con más frecuencia con esa mano, así que en endgame órdago (lanzar o
     * aceptar) se relajan los pisos. Esto es por encima del boost ya bakeado
     * en `finalStrength` por `mergePartnerGestures` (que sólo lifta el lance
     * concreto de la seña); el all-in compara todos los lances al showdown.
     */
    private fun partnerHasStrongSignal(gameState: GameState, aiPlayer: Player): Boolean {
        val partner = gameState.players.firstOrNull {
            it.team == aiPlayer.team && it.id != aiPlayer.id
        } ?: return false
        val gesture = gameState.knownGestures[partner.id] ?: return false
        return when (val m = getGestureMeaning(gesture.gestureKind)) {
            is GestureMeaning.Pares -> when (val play = m.play) {
                is ParesPlay.Duples -> true
                is ParesPlay.Medias -> play.rank == Rank.REY
                is ParesPlay.Pares -> play.rank == Rank.REY
                else -> false
            }
            // 31 y 30 son los dos juegos que ganan al resto del campo; ambos
            // marcan al equipo como ganador casi seguro del lance JUEGO al
            // showdown del all-in (#16 c strategy-reviewer ampliación).
            is GestureMeaning.Juego -> m.value == 31 || m.value == 30
            else -> false
        }
    }

    /**
     * Endgame ordago — módulo de órdago OFENSIVO según el modelo R1-R5
     * (ver `docs/context/ORDAGO_STRATEGY.md`). Evalúa en orden de prioridad:
     *
     *  1) **R1.b "Cortar la jugada"** — proyección del recuento final indica
     *     que el rival cierra al cobro de la ronda y yo no, AUNQUE el
     *     marcador actual sea ajustado: órdago para cortar el cobro al final.
     *  2) **R1.a Hail-Mary** (desventaja crítica `my < opp && opp >= 33`):
     *     mano excelente (`>=85`) directo, o mano media (`>=70`) si el
     *     proxy de "rival flojo" (Q3: rival pidió Mus + descartó ≥3) lo
     *     confirma.
     *  3) **R5 Endgame ajustado** (ambos `>=33` && |diff| `<=2`): mano fuerte
     *     `>=85` → órdago para cortar el recuento del lance (timing).
     *  4) **Q2 Último lance apostable** (en endgame con ventaja `>=3` pero
     *     siendo este lance el último apostable con mi mano decente):
     *     órdago para no dejar al rival sumar al recuento.
     *
     * Si nada aplica → null y la apertura por bandas normal sigue. El wrapper
     * de `decideByPhase` ya descarta el órdago si soy apoyo (`playSupport`):
     * este módulo SOLO dispara para capitán del lance (#20).
     */
    // Complejidad/returns/condiciones pre-existentes del módulo de órdago endgame
    // (#16); reducirlos es tarea aparte con validación de simulador (comportamiento
    // fijado por el snapshot).
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition")
    private fun decideEndgameOrdago(
        strength: Int,
        finalStrength: HandStrength,
        aiPlayer: Player,
        gameState: GameState
    ): Pair<GameAction, String>? {
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val myScore = gameState.score[aiPlayer.team] ?: 0
        val opponentScore = gameState.score[opponentTeam] ?: 0
        val diff = myScore - opponentScore

        val partnerStrong = partnerHasStrongSignal(gameState, aiPlayer)
        val pairBonus = if (partnerStrong) ENDGAME_ORDAGO_PAIR_HINT_BONUS else 0

        // 1) R1.b "Cortar la jugada" — si el rival va a cerrar la partida al
        //    recuento (envites pendientes que probablemente pierdo), aunque
        //    el marcador actual no sea endgame ajustado. Modelo simple:
        //    suma agreedBets que el rival se llevará si gana el showdown
        //    (mi finalStrength_lance < umbral). NO suma bonos de jugada
        //    (pares/juego declarados) — modelo rico queda como follow-up
        //    si playtest pide más cobertura.
        val oppProjectedPoints = projectOpponentPointsAtRoundEnd(gameState, finalStrength)
        val myProjectedPoints = projectMyPointsAtRoundEnd(gameState, finalStrength)
        if (opponentScore + oppProjectedPoints >= 40 &&
            myScore + myProjectedPoints < 40 &&
            strength >= R1B_MY_HAND_FLOOR - pairBonus
        ) {
            val why = "R1.b cortar la jugada (oppProj +$oppProjectedPoints → ${opponentScore + oppProjectedPoints}, " +
                "myProj +$myProjectedPoints → ${myScore + myProjectedPoints}, strength $strength)"
            return Pair(GameAction.Órdago, "Reason: $why")
        }

        // 2) R1.a / R1.a' Hail-Mary en desventaja crítica.
        //    Aplica cuando voy DETRÁS y se cumple UNA de:
        //    - rival está en zona de cierre (opp ≥ 33), OR
        //    - rival está cerca (opp ≥ 30) Y yo MUY por detrás (diff ≤ -10).
        //    La segunda rama sustituye a las legacy "Desperation" y "Block win"
        //    (eliminadas tras A/B sim 2026-05-28: disparaban 36% del spam).
        if (myScore < opponentScore &&
            opponentScore >= 30 &&
            (opponentScore >= ENDGAME_BORDER_SCORE || diff <= -10)
        ) {
            // R1.a: mano excelente directa (≥90 calibrado A/B 2026-05-28).
            // NO pasa por hasBetterLanceAhead — con la nuts y rival cerca de
            // cerrar, la urgencia del Hail-Mary manda: lanzar YA en cualquier
            // lance es +EV vs esperar (rival puede cerrarte en su siguiente
            // turno). Sí mantiene el gate "ganar este lance normal me cierra".
            // Gate AMPLE_DIFF (A/B 2026-05-29): R1 del usuario exige
            // "diferencia muy amplia"; con diff ∈ [-1,-4] (32-33, 33-35) un
            // envite normal rinde más con mano excelente.
            if (strength >= ENDGAME_ORDAGO_HAILMARY_FLOOR - pairBonus &&
                diff <= R1A_AMPLE_DIFF
            ) {
                if (myScore + ENDGAME_STANDARD_CLOSE_BET >= 40) return null
                val why = "R1.a Hail-Mary mano excelente (oppScore $opponentScore, " +
                    "myScore $myScore, strength $strength, partnerStrong=$partnerStrong)"
                return Pair(GameAction.Órdago, "Reason: $why")
            }
            // R1.a': mano media (≥70) + proxy "rival flojo" (Q3). Aplica solo
            // en la zona estricta de R1.a (opp ≥ 33). Con mano MEDIA sí
            // pasa por hailMaryGatesPass: no quemar el órdago si hay lance
            // posterior con mano decente (caso típico del backlog #16).
            if (opponentScore >= ENDGAME_BORDER_SCORE &&
                strength >= ENDGAME_ORDAGO_HAILMARY_LOOSE_FLOOR - pairBonus &&
                opponentLooseSignal(gameState, aiPlayer)
            ) {
                if (!hailMaryGatesPass(gameState, finalStrength, myScore)) return null
                val why = "R1.a' Hail-Mary + proxy rival flojo (oppScore $opponentScore, " +
                    "myScore $myScore, strength $strength)"
                return Pair(GameAction.Órdago, "Reason: $why")
            }
            // R1.a'' "Desesperación catastrófica": diff ≤ -15 + opp ≥ 33 +
            // mano remotamente jugable (≥60). Cubre el caso del log
            // 2026-05-29: rival 17 vs humano 36, max strength 75 (Chica 2
            // ases), sin proxy → R1.a/R1.a' no disparan → IA se "rinde".
            // El downside del Hail-Mary es marginal (perderías igual en
            // 2-3 manos); el upside es flippear con fold-equity del rival.
            if (opponentScore >= ENDGAME_BORDER_SCORE &&
                diff <= ENDGAME_CATASTROPHE_DIFF &&
                strength >= ENDGAME_ORDAGO_CATASTROPHE_FLOOR - pairBonus
            ) {
                if (!hailMaryGatesPass(gameState, finalStrength, myScore)) return null
                val why = "R1.a'' Desesperación catastrófica (oppScore $opponentScore, " +
                    "myScore $myScore, diff $diff, strength $strength)"
                return Pair(GameAction.Órdago, "Reason: $why")
            }
        }

        // 3) R5 Endgame ajustado — ambos en zona de cierre con diff mínima.
        //    Órdago timing-driven con mano fuerte (≥85).
        if (myScore >= ENDGAME_BORDER_SCORE &&
            opponentScore >= ENDGAME_BORDER_SCORE &&
            kotlin.math.abs(diff) <= ENDGAME_TIGHT_DIFF &&
            strength >= ENDGAME_ORDAGO_TIGHT_FLOOR - pairBonus
        ) {
            val why = "R5 endgame ajustado (myScore $myScore, oppScore $opponentScore, " +
                "diff $diff, strength $strength)"
            return Pair(GameAction.Órdago, "Reason: $why")
        }

        // 4) Q2 último lance apostable con mano fuerte en endgame con ventaja
        //    MODERADA (diff ∈ [3, Q2_MAX_DIFF=5]). Calibración A/B 2026-05-28:
        //    sin tope superior en diff, Q2 disparaba 25% del total de órdagos
        //    en sim simétrico (3190/10000 partidas, ventajas brutales con
        //    cualquier mano ≥80). Restringido a ventaja contenida + piso 85.
        if (myScore >= ENDGAME_BORDER_SCORE &&
            diff in 3..Q2_MAX_DIFF &&
            strength >= ENDGAME_ORDAGO_LAST_LANCE_FLOOR - pairBonus
        ) {
            val remainingLances = remainingLancesAfter(gameState.gamePhase)
            val hasBetterLanceAhead = remainingLances.any { phase ->
                strengthFor(phase, finalStrength) >= ENDGAME_REMAINING_LANCE_FLOOR
            }
            if (!hasBetterLanceAhead) {
                val why = "Q2 último lance apostable (myScore $myScore, " +
                    "diff $diff, strength $strength)"
                return Pair(GameAction.Órdago, "Reason: $why")
            }
        }

        return null
    }

    /**
     * Gate común para R1.a / R1.a' (Hail-Mary): no lanzar si:
     *  - Ganar el lance NORMAL ya me cierra a mí (basta Envido grande).
     *  - Me queda un lance posterior con mano decente (no quemar aquí).
     */
    private fun hailMaryGatesPass(
        gameState: GameState,
        finalStrength: HandStrength,
        myScore: Int
    ): Boolean {
        if (myScore + ENDGAME_STANDARD_CLOSE_BET >= 40) return false
        val remainingLances = remainingLancesAfter(gameState.gamePhase)
        val hasBetterLanceAhead = remainingLances.any { phase ->
            strengthFor(phase, finalStrength) >= ENDGAME_REMAINING_LANCE_FLOOR
        }
        return !hasBetterLanceAhead
    }

    /**
     * R4.e — Lectura del patrón del rival: true si CUALQUIER jugador del
     * equipo rival ha lanzado un envido FUERTE (`amount >= STRONG_BET_THRESHOLD`)
     * o un órdago (representado como 40 en `playerMaxBetThisRound`) en algún
     * LANCE PREVIO de esta ronda. Su mano es consistentemente alta → este
     * órdago al que respondo es real, no farol → endurecer acceptThreshold.
     */
    private fun opponentBetStrongInPriorLance(gameState: GameState, aiPlayer: Player): Boolean {
        return gameState.players.any { p ->
            p.team != aiPlayer.team &&
                (gameState.playerMaxBetThisRound[p.id] ?: 0) >= STRONG_BET_THRESHOLD
        }
    }

    /**
     * R1.a' proxy "rival flojo" (Q3 minimal): el rival pidió Mus en esta
     * ronda (`musRoundCount >= 1` ⇒ hubo Mus colectivo) Y al menos un
     * jugador del equipo rival descartó ≥3 cartas en el último ciclo de
     * descarte (`discardCounts`). Heurística simple, falible (slow-play del
     * rival la engaña), pero suficiente para habilitar Hail-Mary con manos
     * medias en desventaja crítica sin abrir spam.
     */
    private fun opponentLooseSignal(gameState: GameState, aiPlayer: Player): Boolean {
        if (gameState.musRoundCount < 1) return false
        return gameState.players.any { p ->
            p.team != aiPlayer.team &&
                (gameState.discardCounts[p.id] ?: 0) >= OPPONENT_LOOSE_DISCARD_MIN
        }
    }

    /**
     * Proyección de puntos del rival al recuento final (modelo simple, Q4 v1).
     * Suma agreedBets de lances donde mi `finalStrength_lance` es baja (rival
     * probablemente gana el showdown). NO suma bonos por jugada declarada —
     * modelo rico queda como follow-up. Sesgo CONSERVADOR a sobreestimar al
     * rival (umbral 55 deja más lances en el saco del rival que el modelo
     * #33 follow-up que usaba 50 con sesgo opuesto: subcontar).
     */
    private fun projectOpponentPointsAtRoundEnd(
        gameState: GameState,
        finalStrength: HandStrength
    ): Int {
        return gameState.agreedBets.entries.sumOf { (lance, amount) ->
            if (lance == gameState.gamePhase) 0
            else {
                val myStrength = strengthFor(lance, finalStrength)
                if (myStrength < R1B_OPPONENT_WIN_THRESHOLD) amount else 0
            }
        }
    }

    /**
     * Proyección de mis puntos al recuento final (espejo del anterior). Sumo
     * agreedBets donde mi `finalStrength_lance >= 55` (probablemente gano).
     * Mismo modelo simple sin bonos de jugada.
     */
    private fun projectMyPointsAtRoundEnd(
        gameState: GameState,
        finalStrength: HandStrength
    ): Int {
        return gameState.agreedBets.entries.sumOf { (lance, amount) ->
            if (lance == gameState.gamePhase) 0
            else {
                val myStrength = strengthFor(lance, finalStrength)
                if (myStrength >= R1B_OPPONENT_WIN_THRESHOLD) amount else 0
            }
        }
    }

    private fun remainingLancesAfter(phase: GamePhase): List<GamePhase> = when (phase) {
        GamePhase.GRANDE -> listOf(GamePhase.CHICA, GamePhase.PARES, GamePhase.JUEGO)
        GamePhase.CHICA -> listOf(GamePhase.PARES, GamePhase.JUEGO)
        GamePhase.PARES -> listOf(GamePhase.JUEGO)
        else -> emptyList()
    }

    private fun strengthFor(phase: GamePhase, hs: HandStrength): Int = when (phase) {
        GamePhase.GRANDE -> hs.grande
        GamePhase.CHICA -> hs.chica
        GamePhase.PARES -> hs.pares
        GamePhase.JUEGO -> hs.juego
        else -> 0
    }

    // Complejidad pre-existente de la apertura por bandas; reducirla es tarea aparte
    // con validación de simulador (el comportamiento lo fija el snapshot).
    @Suppress("CyclomaticComplexMethod")
    private fun decideInitialBet(
        strength: Int,
        finalStrength: HandStrength,
        aiPlayer: Player,
        gameState: GameState
    ): Pair<GameAction, String> {
        val opponentTeam = if (aiPlayer.team == "teamA") "teamB" else "teamA"
        val myTeamScore = gameState.score[aiPlayer.team] ?: 0
        val opponentScore = gameState.score[opponentTeam] ?: 0
        val scoreDifference = myTeamScore - opponentScore

        // Módulo R1-R5 endgame ordago (#16). Cubre TODOS los casos de órdago
        // proactivo: R1.b cortar la jugada, R1.a/R1.a' Hail-Mary desventaja
        // crítica (con la rama ampliada a opp ∈ [30, 32] que sustituyó a las
        // legacy Desperation + Block win tras A/B sim 2026-05-28), R5 endgame
        // ajustado timing, Q2 último lance apostable. Devuelve null si no
        // aplica → apertura por bandas normal sigue.
        decideEndgameOrdago(strength, finalStrength, aiPlayer, gameState)?.let { return it }
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

        // 3) Farol barato (importe 2). Solo si alguien ya pasó (robo) y marcador
        // no delicado. CLAVE (usuario 2026-05-22): el robo es seguro sobre todo
        // desde el POSTRE. `isLate` (pos2 y pos3) mezclaba penúltimo y postre,
        // pero NO son iguales: si soy penúltimo, ha pasado un rival pero el
        // postre AÚN no ha hablado y puede cazar/subir mi farol. Se controla por
        // `rivalsBehind` = rivales del lance que actúan DESPUÉS que yo: 0
        // (postre / último rival) → robo pleno; 1 → muy prudente; ≥2 → muy raro
        // (no absoluto: nada de "nunca" categórico).
        val rivalsBehind = order.drop(myPos + 1).count { p ->
            p.team != aiPlayer.team &&
                (gameState.playersInLance.isEmpty() || p.id in gameState.playersInLance)
        }
        if (effStrength < OPEN_MID_BAND_FLOOR && stealSpot && !scoreRisky) {
            val bluffP = when (rivalsBehind) {
                0 -> profile.bluffProbPostre      // postre / sin rivales detrás: robo pleno
                1 -> profile.bluffProbPenultimate // un rival aún por hablar: prudente (puede cazarme)
                else -> profile.bluffProbEarly    // ≥2 detrás: muy raro, pero NO absoluto
            }
            // OJO: el rng.nextDouble() se consume SIEMPRE que se entra al bloque
            // (igual que la versión previa), aunque bluffP sea 0 — si no, se
            // desincroniza el stream del rng y el a/b del simulador deja de ser
            // limpio (mismas semillas → trayectorias distintas).
            if (rng.nextDouble() < bluffP) {
                return Pair(GameAction.Envido(2),
                    "Reason: Farol de robo ($strength, rivalsBehind=$rivalsBehind, p=${"%.2f".format(bluffP)})")
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
    private val discardThreshold = profile.discardThreshold
    private val duplesBonus = profile.duplesBonus
    private val deadPenalty = profile.deadPenalty

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

        // Regla dura: 3 figuras + 1 no-figura.
        // (#39) Solo se persigue el 31 manteniendo las 3 figuras si entre
        // ellas hay AL MENOS UN REY (Rey/Tres): ahí hay respaldo de Grande y
        // la caza del 31 es legítima. Con 3 figuras flojas (caballo/sota, sin
        // rey) NO se persigue el 31 a ciegas — se cae al scoring, que descarta
        // las figuras sueltas y roba buscando reyes (siempre conserva el par,
        // pues 3 figuras no-rey implican Caballo/Sota emparejadas). Evita el
        // "quedarse demasiadas figuras a por el 31 y regalar reyes al resto".
        val figures = hand.filter { it.rank in figureRanks }
        val hasKingFigure = figures.any { it.rank == Rank.REY || it.rank == Rank.TRES }
        if (figures.size == 3 && hasKingFigure) {
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
            // Los empates de Juego los gana quien está más cerca de mano. Con un
            // juego no-31 (empatable a menudo), cuantos más RIVALES han actuado
            // antes peor: un 32 en postre con dos rivales delante pierde a
            // postre, y además puede perder contra cualquier 31 del rival.
            //
            // Sustituye la penalización por POSICIÓN ABSOLUTA (era
            // `playerPositionInTurn * 6`) que penalizaba al compañero como si
            // fuera rival. Mismo modelo que el 31: `rivalsAhead` cuenta solo
            // rivales (no compañero). Multiplicador *10 (era *6 sobre pos
            // absoluta) para endurecer — el simulador a 50.000p mostraba que
            // 32-40 no-mano aceptaba envites con win-rate insuficiente.
            if (juegoValue in 32..40 && rivalsAhead > 0) {
                val posPenalty = rivalsAhead * 10
                juegoStrength -= posPenalty
                explanation.appendLine(
                    "     - Penalización por $rivalsAhead rival(es) delante con juego no-31 -> -$posPenalty pts"
                )
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

    private fun getGestureMeaning(kind: GestureKind): GestureMeaning? = when (kind) {
        // Para las señas de pares, asumimos una jugada representativa fuerte.
        GestureKind.DUPLES_ALTOS -> GestureMeaning.Pares(ParesPlay.Duples(Rank.REY, Rank.REY))
        GestureKind.DUPLES_BAJOS -> GestureMeaning.Pares(ParesPlay.Duples(Rank.SOTA, Rank.AS))
        GestureKind.REYES_3 -> GestureMeaning.Pares(ParesPlay.Medias(Rank.REY))
        GestureKind.ASES_3 -> GestureMeaning.Pares(ParesPlay.Medias(Rank.AS))
        GestureKind.REYES_2 -> GestureMeaning.Pares(ParesPlay.Pares(Rank.REY))
        GestureKind.ASES_2 -> GestureMeaning.Pares(ParesPlay.Pares(Rank.AS))
        GestureKind.JUEGO_31 -> GestureMeaning.Juego(31)
        GestureKind.CIEGA -> GestureMeaning.Ciega
    }

    // ¿Este observador intercepta esta seña rival? Determinista y ESTABLE
    // dentro de la ronda (no parpadea entre lances): hash de mano (rota cada
    // ronda) + emisor + observador + seña. Re-tira solo al cambiar de ronda.
    private fun opponentSignPerceived(
        gameState: GameState,
        observer: Player,
        gesturerId: String,
        kind: GestureKind
    ): Boolean {
        val seed = "${gameState.manoPlayerId}|$gesturerId|${observer.id}|${kind.name}"
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
        val ownGestureName = if (ownGesture != null) ownGesture.gestureKind.label else "(no pasada)"
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
                val gestureName = gesture.gestureKind.label
                logBuilder.appendLine("   - (Offensive) Partner ${gesturer.name} has '$gestureName'. Merging strength.")
                val gesturerIsMano = gameState.manoPlayerId == gesturer.id

                // Cada seña del compañero implica info parcial sobre Grande y Chica además
                // de la jugada concreta. Aplicamos un boost moderado al lance que cuadra:
                // las 2 cartas que no vemos podrían tirar la jugada del compañero, por eso
                // no asumimos el máximo.
                val grandeBoost = partnerGrandeBoost(gesture.gestureKind)
                val chicaBoost = partnerChicaBoost(gesture.gestureKind)
                if (grandeBoost > teamStrength.grande) {
                    teamStrength = teamStrength.copy(grande = grandeBoost)
                    logBuilder.appendLine("     -> Partner gesture boosts Grande to $grandeBoost.")
                }
                if (chicaBoost > teamStrength.chica) {
                    teamStrength = teamStrength.copy(chica = chicaBoost)
                    logBuilder.appendLine("     -> Partner gesture boosts Chica to $chicaBoost.")
                }

                when (val meaning = getGestureMeaning(gesture.gestureKind)) {
                    is GestureMeaning.Pares -> {
                        val gestureStrength = getParesPlayStrength(meaning.play, gesturerIsMano)
                        teamStrength = teamStrength.copy(pares = max(teamStrength.pares, gestureStrength))
                        logBuilder.appendLine("     -> Partner Pares Strength is ~$gestureStrength (mano=$gesturerIsMano). Team Pares Strength is now ${teamStrength.pares}.")
                    }
                    is GestureMeaning.Juego -> {
                        // 31 sin ser mano puede perder ante otro 31 más cerca de mano:
                        // mismo trato que para la propia mano en evaluateHand.
                        // #19 PARTE B pieza 3: derivar de rivalsAhead-del-gesturer en
                        // lugar de 85 plano. P(pierdo) = 1−(1−0.10)^rivalsAhead, mismo
                        // modelo que el self en evaluateJuego. Un 31 del compañero
                        // que actúa el penúltimo (1 rival delante) vale ~90; del
                        // postre con 2 rivales delante, ~81. Coherente con la curva
                        // propia y con el override #19 PARTE B del aceptante.
                        val juegoStrength = if (gesturerIsMano) {
                            100
                        } else {
                            val gesturerOrder = gameLogic.getTurnOrderedPlayers(
                                gameState.players, gameState.manoPlayerId
                            )
                            val gPos = gesturerOrder.indexOfFirst { it.id == gesturer.id }
                            val gRivalsAhead =
                                if (gPos > 0) gesturerOrder.take(gPos).count { it.team != gesturer.team } else 0
                            val pLose = 1.0 - Math.pow(JUEGO_HOLD_PROB_PER_RIVAL, gRivalsAhead.toDouble())
                            (100 - (pLose * 100).toInt()).coerceIn(0, 100)
                        }
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
                if (!opponentSignPerceived(gameState, aiPlayer, playerId, gesture.gestureKind)) {
                    logBuilder.appendLine("   - (Defensive) Seña de ${gesturer.name} NO interceptada por ${aiPlayer.name}.")
                    continue
                }
                val gestureName = gesture.gestureKind.label
                logBuilder.appendLine("   - (Defensive) Opponent ${gesturer.name} has '$gestureName' (interceptada).")

                when (val meaning = getGestureMeaning(gesture.gestureKind)) {
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
                val oppG = partnerGrandeBoost(gesture.gestureKind)
                val oppC = partnerChicaBoost(gesture.gestureKind)
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
    internal fun partnerGrandeBoost(kind: GestureKind): Int = when (kind) {
        GestureKind.REYES_3 -> 90   // 3 Reyes/Treses → dispara Envido seguro (>80)
        GestureKind.JUEGO_31 -> 35   // #14: 31 da figuras pero 0-1 Reyes modal; señal Grande débil
        GestureKind.REYES_2 -> 65   // par alto: activa bluff y empuja a Envido si ya tienes algo
        GestureKind.DUPLES_ALTOS -> 78  // #23: 2 reyes garantizados sin riesgo de descarte; entre reyes_2 y reyes_3
        else -> 0
    }

    // Boost al lance Chica del equipo según la seña del compañero.
    internal fun partnerChicaBoost(kind: GestureKind): Int = when (kind) {
        GestureKind.ASES_3 -> 90    // 3 Ases/Doses → dispara Envido seguro
        GestureKind.ASES_2 -> 65    // par bajo
        GestureKind.DUPLES_BAJOS -> 50
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
            // #13: el bonus de mano resuelve EMPATES entre jugadas idénticas
            // (frecuente en par único). En Duples/Medias el empate exacto entre
            // equipos es casi imposible (4 cartas, 12 rangos) y el +15 solo
            // cruzaba la jerarquía de showdown: una Medias-Rey mano (84+15=99)
            // superaba a Duples bajos no-mano, cuando CUALQUIER duples gana a
            // CUALQUIER medias. Por eso solo el par único recibe bonus de mano.
            strength += when (paresPlay) {
                is ParesPlay.Pares -> manoParesBonus(getPairingRankValue(paresPlay.rank))
                else -> 0
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


