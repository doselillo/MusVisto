package com.doselfurioso.musvisto.sim

import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AIDecision
import com.doselfurioso.musvisto.logic.AILogic
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Simulador headless de partidas IA-vs-IA.
 *
 * NO se ejecuta en la suite normal: gated por `-Dmusvisto.sim=true`.
 * Uso: ./gradlew testDebugUnitTest -Dmusvisto.sim=true -Dmusvisto.games=200
 *
 * Reproduce la orquestación de GameViewModel (reparto, gestos al 70%,
 * makeDecision/processAction, declaración PARES/JUEGO_CHECK, scoring de ronda,
 * rotación de mano) SIN corrutinas ni delays. Vuelca a docs/ai_logs/ un
 * resumen con agregados y un Diagnóstico en lenguaje claro.
 */
class GameSimulatorTest {

    private class Stats {
        var games = 0
        var teamAWins = 0
        var teamBWins = 0
        var totalRounds = 0
        var ordagoGames = 0
        var stalledGames = 0

        var musDecisions = 0
        var noMusDecisions = 0
        var deals = 0
        var discardCycles = 0

        // Delegación de corte de Mus (#20): el "primero" del equipo que va a
        // señalizar cede el corte al capitán (Mus) salvo el break ocasional.
        // Mide la FIDELIDAD del sim: si es 0, la delegación no se ejerce
        // (bug D: startRound no rellenaba pendingGestures, gate de la delegación).
        var delegationFired = 0    // "#20 delego" → pide Mus cediendo el corte
        var delegationBreak = 0    // "#20 break"  → corta excepcionalmente

        // Por lance: phase -> contadores
        val paso = mutableMapOf<GamePhase, Int>()
        val envido = mutableMapOf<GamePhase, Int>()
        val envidoAmt = mutableMapOf<GamePhase, Int>()
        val quiero = mutableMapOf<GamePhase, Int>()
        val noQuiero = mutableMapOf<GamePhase, Int>()
        val ordago = mutableMapOf<GamePhase, Int>()
        val supportFired = mutableMapOf<GamePhase, Int>()

        // 31 en postre respondiendo a envite de Juego
        var postre31Faced = 0
        var postre31Quiero = 0
        var postre31NoQuiero = 0
        var postre31Subio = 0

        // CALIDAD: ¿las decisiones tienen sentido? Se mide contra el showdown
        // real (manos fijas en la ronda): quién gana el lance de verdad.
        var acceptWon = 0          // dijo "quiero" y su equipo gana ese lance
        var acceptLost = 0         // dijo "quiero" y lo pierde
        var acceptNet = 0          // tantos netos de aceptar (gana +amount / pierde -amount)
        var envidoWon = 0          // abrió envite y su equipo gana el lance
        var envidoLost = 0         // abrió envite y lo pierde (incluye faroles)

        // INSTRUMENTACIÓN TEMPORAL: aceptar por lance y por tamaño de envite.
        val acceptWonByPhase = mutableMapOf<GamePhase, Int>()
        val acceptLostByPhase = mutableMapOf<GamePhase, Int>()
        val acceptNetByPhase = mutableMapOf<GamePhase, Int>()
        // Por tamaño del envite aceptado.
        val acceptWonByAmt = mutableMapOf<Int, Int>()
        val acceptLostByAmt = mutableMapOf<Int, Int>()
        val acceptNetByAmt = mutableMapOf<Int, Int>()

        // SEGMENTACIÓN (Fase 1·E): aceptar/abrir por POSICIÓN (nº de rivales que
        // actúan antes que yo en el lance) y por MARCADOR (delante/detrás/
        // igualado/rival≥33). Aísla si la IA acepta peor desde mala posición
        // (rivales por delante = más probable que alguien me gane) o bajo presión
        // de marcador, cosas que el agregado global esconde.
        val acceptWonByPos = mutableMapOf<Int, Int>()
        val acceptLostByPos = mutableMapOf<Int, Int>()
        val acceptNetByPos = mutableMapOf<Int, Int>()
        val acceptWonByScore = mutableMapOf<String, Int>()
        val acceptLostByScore = mutableMapOf<String, Int>()
        val acceptNetByScore = mutableMapOf<String, Int>()
        val envidoWonByPos = mutableMapOf<Int, Int>()
        val envidoLostByPos = mutableMapOf<Int, Int>()
        val envidoWonByScore = mutableMapOf<String, Int>()
        val envidoLostByScore = mutableMapOf<String, Int>()

        fun inc(m: MutableMap<GamePhase, Int>, p: GamePhase) { m[p] = (m[p] ?: 0) + 1 }
    }

    private val bettingLances = listOf(GamePhase.GRANDE, GamePhase.CHICA, GamePhase.PARES, GamePhase.JUEGO)

    @Test
    fun `simulate AI vs AI games and emit analysis`() {
        assumeTrue(
            "Simulador desactivado (usa -Dmusvisto.sim=true para ejecutarlo)",
            System.getProperty("musvisto.sim") == "true"
        )
        val games = System.getProperty("musvisto.games")?.toIntOrNull() ?: 100
        val batches = (System.getProperty("musvisto.batches")?.toIntOrNull() ?: 1).coerceAtLeast(1)

        // Fase 2 — ARNÉS DE DECISIÓN AISLADA (-Dmusvisto.mode=decisions). Modo
        // aparte que NO juega partidas: evalúa makeDecision sobre un corpus FIJO de
        // manos independientes y lo puntúa contra el showdown real. Como el corpus
        // es idéntico entre corridas, el a/b aísla SOLO el cambio de decisión →
        // señal causal, monótona e inmune al caos de trayectoria del modo partida-
        // completa. Reusa -Dmusvisto.batches=K como lotes de semilla disjuntos para
        // la banda de ruido del propio arnés (prueba anti-caos).
        if (System.getProperty("musvisto.mode") == "decisions") {
            val scenarios = System.getProperty("musvisto.scenarios")?.toIntOrNull() ?: 5000
            val report = runDecisionHarness(scenarios, batches)
            println(report)
            writeReport(report)
            return
        }

        val stats = Stats()

        // K lotes de `games` partidas con rangos de semilla DISJUNTOS (lote b →
        // [b*games, b*games+games)). Todo se acumula en un único Stats para el
        // informe detallado; en la frontera de cada lote tomamos un snapshot de
        // los contadores clave para derivar la métrica por-lote y, entre lotes,
        // su media ± σ (la banda de ruido). Con batches=1 el comportamiento es
        // idéntico al de antes (un solo lote, semillas 0..games-1, sin banda).
        val snapshots = mutableListOf(snapshot(stats))
        for (b in 0 until batches) {
            val base = b.toLong() * games
            repeat(games) { i -> playOneGame(seed = base + i, stats = stats) }
            snapshots += snapshot(stats)
        }
        val batchMetrics = (1..batches).map { batchMetricsBetween(snapshots[it - 1], snapshots[it], games) }

        val report = buildReport(stats, games) +
            if (batches >= 2) "\n" + noiseSection(batchMetrics, batches, games) else ""
        println(report)
        writeReport(report)
    }

    // --- Driver de una partida ---

    private fun players(): List<Player> = listOf(
        Player(id = "p1", name = "A1", avatarResId = 0, isAi = true, team = "teamA"),
        Player(id = "p4", name = "B1", avatarResId = 0, isAi = true, team = "teamB"),
        Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
        Player(id = "p2", name = "B2", avatarResId = 0, isAi = true, team = "teamB")
    )

    private fun playOneGame(seed: Long, stats: Stats) {
        val rng = Random(seed)
        val gameLogic = MusGameLogic(rng)
        val aiLogic = AILogic(gameLogic, rng)
        val roster = players()
        // Alternar mano inicial por partida (seed par → p1/teamA, impar → p4/teamB)
        // para eliminar el bias estructural de "siempre arranca teamA como mano".
        var manoId = if (seed % 2L == 0L) roster[0].id else roster[1].id

        stats.games++
        var state = startRound(gameLogic, roster, manoId, mapOf("teamA" to 0, "teamB" to 0), rng, stats)
        var actions = 0
        val maxActions = 4000

        while (state.gamePhase != GamePhase.GAME_OVER) {
            if (actions++ > maxActions) { stats.stalledGames++; return }

            when (state.gamePhase) {
                GamePhase.ROUND_OVER -> {
                    val scored = gameLogic.scoreRound(state)
                    val bd = scored.scoreBreakdown ?: run { stats.stalledGames++; return }
                    val newScore = mapOf(
                        "teamA" to ((state.score["teamA"] ?: 0) + bd.teamAScoreDetails.sumOf { it.points }),
                        "teamB" to ((state.score["teamB"] ?: 0) + bd.teamBScoreDetails.sumOf { it.points })
                    )
                    val winner = when {
                        (newScore["teamA"] ?: 0) >= 40 -> "teamA"
                        (newScore["teamB"] ?: 0) >= 40 -> "teamB"
                        else -> null
                    }
                    stats.totalRounds++
                    if (winner != null) {
                        if (winner == "teamA") stats.teamAWins++ else stats.teamBWins++
                        return
                    }
                    val lastIdx = roster.indexOfFirst { it.id == manoId }
                    manoId = roster[(lastIdx - 1 + roster.size) % roster.size].id
                    state = startRound(gameLogic, roster, manoId, newScore, rng, stats)
                }

                GamePhase.PARES_CHECK, GamePhase.JUEGO_CHECK -> {
                    val ordered = gameLogic.getTurnOrderedPlayers(state.players, state.manoPlayerId)
                    var temp = state
                    for (p in ordered) {
                        val has = if (temp.gamePhase == GamePhase.PARES_CHECK) {
                            gameLogic.getHandPares(p.hand).strength > 0
                        } else {
                            gameLogic.getHandJuegoValue(p.hand) >= 31
                        }
                        val act = if (has) GameAction.Tengo else GameAction.NoTengo
                        val info = LastActionInfo(p.id, act)
                        temp = temp.copy(
                            lastAction = info,
                            currentLanceActions = temp.currentLanceActions + (p.id to info)
                        )
                    }
                    state = gameLogic.resolveDeclaration(temp)
                }

                else -> {
                    val pid = state.currentTurnPlayerId ?: run { stats.stalledGames++; return }
                    val player = state.players.find { it.id == pid } ?: run { stats.stalledGames++; return }
                    val prevPhase = state.gamePhase
                    val decision = aiLogic.makeDecision(state, player)
                    recordDecision(stats, state, player, decision, gameLogic)

                    var next = state
                    if (decision.action is GameAction.ConfirmDiscard) {
                        next = next.copy(selectedCardsForDiscard = decision.cardsToDiscard)
                    }
                    next = gameLogic.processAction(next, decision.action, pid)

                    if (prevPhase == GamePhase.MUS && next.gamePhase == GamePhase.DISCARD) {
                        stats.discardCycles++
                    }
                    if (next.ordagoInfo != null) stats.ordagoGames++
                    state = next
                }
            }
        }

        // El motor puede llegar a GAME_OVER directamente (p. ej. un órdago que
        // cierra la partida) sin pasar por nuestra rama ROUND_OVER, que es la
        // que cuenta y hace return. Atribuimos aquí ese ganador final.
        when (state.winningTeam) {
            "teamA" -> { stats.teamAWins++; stats.totalRounds++ }
            "teamB" -> { stats.teamBWins++; stats.totalRounds++ }
            else -> stats.stalledGames++
        }
    }

    private fun startRound(
        gameLogic: MusGameLogic,
        roster: List<Player>,
        manoId: String,
        score: Map<String, Int>,
        rng: Random,
        stats: Stats
    ): GameState {
        val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, remaining) = gameLogic.dealCards(roster, deck, manoId)
        stats.deals++

        // Señas: mirror fiel de GameViewModel.onEnterMusPhase + triggerAiGestures.
        //  - pendingGestures = PLAN de seña. Es el gate de la delegación de corte
        //    #20 (AILogic.decideMusDelegation exige `id in pendingGestures`); sin
        //    él la delegación NO se ejerce → el sim mediría una IA distinta de la
        //    enviada (bug de fidelidad D del plan del simulador).
        //  - knownGestures = señas efectivamente mostradas, DERIVADAS del plan
        //    (en el VM triggerAiGestures emite cada seña pendiente; headless no
        //    cancela por timing/cambio de fase, así que todo lo planificado se
        //    muestra). La interceptación rival (prob 0.20) la modela AILogic
        //    sobre este map global, no se filtra aquí.
        // Prob 0.90 = PENDING_GESTURE_PROB_AI_PARTNER (todos los compañeros son
        // IA en el sim). Antes el sim rellenaba knownGestures directo a 0.70 y
        // nunca pendingGestures. Se computa la seña primero y solo entonces se
        // tira (igual que el mapNotNull del VM): un jugador sin seña no consume
        // tirada.
        val pending = mutableMapOf<String, Int>()
        val known = mutableMapOf<String, ActiveGestureInfo>()
        for (p in dealt) {
            val g = determineGesture(p, gameLogic) ?: continue
            if (rng.nextFloat() < 0.90f) {
                pending[p.id] = g
                known[p.id] = ActiveGestureInfo(p.id, g)
            }
        }

        return GameState(
            players = dealt,
            deck = remaining,
            score = score,
            manoPlayerId = manoId,
            currentTurnPlayerId = manoId,
            gamePhase = GamePhase.MUS,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus),
            pendingGestures = pending,
            knownGestures = known
        )
    }

    /** Mirror de GameViewModel.determineGesture (la lógica de seña vive en el VM). */
    private fun determineGesture(player: Player, gameLogic: MusGameLogic): Int? {
        val hand = player.hand
        if (hand.isEmpty()) return null
        val reyes = hand.count { it.rank == Rank.REY || it.rank == Rank.TRES }
        val ases = hand.count { it.rank == Rank.AS || it.rank == Rank.DOS }
        val pares = gameLogic.getHandPares(hand)
        val juego = gameLogic.getHandJuegoValue(hand)

        if (pares is ParesPlay.Duples) {
            return if (pares.lowPair.value >= Rank.SOTA.value) R.drawable.duples_altos
            else R.drawable.duples_bajos
        }
        if (juego == 31) return R.drawable.sena_31
        if (reyes >= 3) return R.drawable.reyes_3
        if (ases >= 3) return R.drawable.ases_3
        if (reyes == 2) return R.drawable.reyes_2
        if (ases == 2) return R.drawable.ases_2
        if (pares is ParesPlay.NoPares && juego < 31) return R.drawable.ciega
        return null
    }

    private fun recordDecision(
        stats: Stats,
        state: GameState,
        player: Player,
        decision: AIDecision,
        gameLogic: MusGameLogic
    ) {
        val phase = state.gamePhase
        // Delegación #20 (gate: id en pendingGestures). Cuenta tanto el ceder
        // (Mus) como el break (NoMus excepcional). Es la sonda de fidelidad D.
        if (decision.debugLog.contains("#20 delego")) stats.delegationFired++
        if (decision.debugLog.contains("#20 break")) stats.delegationBreak++

        when (val a = decision.action) {
            is GameAction.Mus -> stats.musDecisions++
            is GameAction.NoMus -> stats.noMusDecisions++
            is GameAction.Paso -> if (phase in bettingLances) stats.inc(stats.paso, phase)
            is GameAction.Envido -> if (phase in bettingLances) {
                stats.inc(stats.envido, phase)
                stats.envidoAmt[phase] = (stats.envidoAmt[phase] ?: 0) + a.amount
            }
            is GameAction.Quiero -> if (phase in bettingLances) stats.inc(stats.quiero, phase)
            is GameAction.NoQuiero -> if (phase in bettingLances) stats.inc(stats.noQuiero, phase)
            is GameAction.Órdago -> if (phase in bettingLances) stats.inc(stats.ordago, phase)
            else -> {}
        }

        if (phase in bettingLances && decision.debugLog.contains("Rol de APOYO")) {
            stats.inc(stats.supportFired, phase)
        }

        // CALIDAD: contra el ganador REAL del lance (showdown con manos fijas).
        if (phase in bettingLances) {
            val lanceWinnerTeam = when (phase) {
                GamePhase.GRANDE -> gameLogic.getGrandeWinner(state)?.team
                GamePhase.CHICA -> gameLogic.getChicaWinner(state)?.team
                GamePhase.PARES -> gameLogic.getParesWinner(state)?.team
                GamePhase.JUEGO -> gameLogic.getJuegoWinner(state)?.team
                else -> null
            }
            if (lanceWinnerTeam != null) {
                val mineWins = lanceWinnerTeam == player.team
                val pos = rivalsAheadInLance(state, player, gameLogic)
                val scoreKey = scoreBucket(state, player)
                when (val a = decision.action) {
                    is GameAction.Quiero -> {
                        val amt = state.currentBet?.amount ?: 0
                        if (mineWins) {
                            stats.acceptWon++; stats.acceptNet += amt
                            stats.acceptWonByPhase[phase] = (stats.acceptWonByPhase[phase] ?: 0) + 1
                            stats.acceptNetByPhase[phase] = (stats.acceptNetByPhase[phase] ?: 0) + amt
                            stats.acceptWonByAmt[amt] = (stats.acceptWonByAmt[amt] ?: 0) + 1
                            stats.acceptNetByAmt[amt] = (stats.acceptNetByAmt[amt] ?: 0) + amt
                            stats.acceptWonByPos[pos] = (stats.acceptWonByPos[pos] ?: 0) + 1
                            stats.acceptNetByPos[pos] = (stats.acceptNetByPos[pos] ?: 0) + amt
                            stats.acceptWonByScore[scoreKey] = (stats.acceptWonByScore[scoreKey] ?: 0) + 1
                            stats.acceptNetByScore[scoreKey] = (stats.acceptNetByScore[scoreKey] ?: 0) + amt
                        } else {
                            stats.acceptLost++; stats.acceptNet -= amt
                            stats.acceptLostByPhase[phase] = (stats.acceptLostByPhase[phase] ?: 0) + 1
                            stats.acceptNetByPhase[phase] = (stats.acceptNetByPhase[phase] ?: 0) - amt
                            stats.acceptLostByAmt[amt] = (stats.acceptLostByAmt[amt] ?: 0) + 1
                            stats.acceptNetByAmt[amt] = (stats.acceptNetByAmt[amt] ?: 0) - amt
                            stats.acceptLostByPos[pos] = (stats.acceptLostByPos[pos] ?: 0) + 1
                            stats.acceptNetByPos[pos] = (stats.acceptNetByPos[pos] ?: 0) - amt
                            stats.acceptLostByScore[scoreKey] = (stats.acceptLostByScore[scoreKey] ?: 0) + 1
                            stats.acceptNetByScore[scoreKey] = (stats.acceptNetByScore[scoreKey] ?: 0) - amt
                        }
                    }
                    is GameAction.Envido -> {
                        if (mineWins) {
                            stats.envidoWon++
                            stats.envidoWonByPos[pos] = (stats.envidoWonByPos[pos] ?: 0) + 1
                            stats.envidoWonByScore[scoreKey] = (stats.envidoWonByScore[scoreKey] ?: 0) + 1
                        } else {
                            stats.envidoLost++
                            stats.envidoLostByPos[pos] = (stats.envidoLostByPos[pos] ?: 0) + 1
                            stats.envidoLostByScore[scoreKey] = (stats.envidoLostByScore[scoreKey] ?: 0) + 1
                        }
                    }
                    else -> {}
                }
            }
        }

        // 31 en postre respondiendo a un envite de Juego
        if (phase == GamePhase.JUEGO && state.currentBet != null &&
            gameLogic.getHandJuegoValue(player.hand) == 31
        ) {
            val ordered = gameLogic.getTurnOrderedPlayers(state.players, state.manoPlayerId)
            if (ordered.lastOrNull()?.id == player.id) {
                stats.postre31Faced++
                when (decision.action) {
                    is GameAction.Quiero -> stats.postre31Quiero++
                    is GameAction.NoQuiero -> stats.postre31NoQuiero++
                    is GameAction.Envido, is GameAction.Órdago -> stats.postre31Subio++
                    else -> {}
                }
            }
        }
    }

    /**
     * Nº de RIVALES (no compañero) que actúan ANTES que yo en este lance. Mide
     * mi exposición en el showdown: cuantos más rivales por delante, más probable
     * que alguno me gane. Restringido a `playersInLance` en Pares/Juego.
     */
    private fun rivalsAheadInLance(state: GameState, player: Player, gameLogic: MusGameLogic): Int {
        val ordered = gameLogic.getTurnOrderedPlayers(state.players, state.manoPlayerId)
            .filter { state.playersInLance.isEmpty() || it.id in state.playersInLance }
        val myIdx = ordered.indexOfFirst { it.id == player.id }
        if (myIdx < 0) return 0
        return ordered.take(myIdx).count { it.team != player.team }
    }

    /** Bucket de marcador desde la óptica de `player` (rival≥33 = presión de cierre). */
    private fun scoreBucket(state: GameState, player: Player): String {
        val myScore = state.score[player.team] ?: 0
        val oppTeam = if (player.team == "teamA") "teamB" else "teamA"
        val oppScore = state.score[oppTeam] ?: 0
        return when {
            oppScore >= 33 -> "rival>=33"
            myScore > oppScore -> "voy delante"
            myScore < oppScore -> "voy detras"
            else -> "igualado"
        }
    }

    private val scoreBuckets = listOf("voy delante", "igualado", "voy detras", "rival>=33")

    // --- Informe + Diagnóstico ---

    private fun pct(n: Int, total: Int): String =
        if (total == 0) "—" else String.format(Locale.US, "%.1f%%", 100.0 * n / total)

    // --- RUIDO / CONFIANZA (Fase 1·C): banda de ruido inter-lote ---

    /** Foto de los contadores ACUMULADOS clave en una frontera de lote. */
    private class Snapshot(
        val aWins: Int,
        val bWins: Int,
        val quiero: Int,
        val noQuiero: Int,
        val acceptNet: Int,
        val mus: Int,
        val noMus: Int
    )

    private fun snapshot(s: Stats) = Snapshot(
        aWins = s.teamAWins,
        bWins = s.teamBWins,
        quiero = bettingLances.sumOf { s.quiero[it] ?: 0 },
        noQuiero = bettingLances.sumOf { s.noQuiero[it] ?: 0 },
        acceptNet = s.acceptNet,
        mus = s.musDecisions,
        noMus = s.noMusDecisions
    )

    /** Métricas (tasas) de UN lote, derivadas de dos snapshots consecutivos. */
    private class BatchMetric(
        val teamAPct: Double,
        val acceptPct: Double,
        val acceptNetPerGame: Double,
        val musPct: Double
    )

    private fun batchMetricsBetween(prev: Snapshot, cur: Snapshot, games: Int): BatchMetric {
        val aWins = cur.aWins - prev.aWins
        val finished = aWins + (cur.bWins - prev.bWins)
        val quiero = cur.quiero - prev.quiero
        val faced = quiero + (cur.noQuiero - prev.noQuiero)
        val mus = cur.mus - prev.mus
        val musTotal = mus + (cur.noMus - prev.noMus)
        return BatchMetric(
            teamAPct = if (finished == 0) 0.0 else 100.0 * aWins / finished,
            acceptPct = if (faced == 0) 0.0 else 100.0 * quiero / faced,
            acceptNetPerGame = (cur.acceptNet - prev.acceptNet).toDouble() / games,
            musPct = if (musTotal == 0) 0.0 else 100.0 * mus / musTotal
        )
    }

    /** Media y desviación estándar muestral (n-1) de una lista. */
    private fun meanStd(xs: List<Double>): Pair<Double, Double> {
        val mean = xs.average()
        val variance = if (xs.size < 2) 0.0
            else xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
        return mean to kotlin.math.sqrt(variance)
    }

    private fun noiseSection(metrics: List<BatchMetric>, batches: Int, games: Int): String {
        val sb = StringBuilder()
        sb.appendLine("=".repeat(56))
        sb.appendLine("RUIDO / CONFIANZA ($batches lotes de $games partidas, semillas disjuntas)")
        sb.appendLine("=".repeat(56))
        fun line(label: String, xs: List<Double>, fmt: String) {
            val (m, sd) = meanStd(xs)
            sb.appendLine(
                "- $label: ${String.format(Locale.US, fmt, m)} ± " +
                    "${String.format(Locale.US, fmt, sd)}  " +
                    "[min ${String.format(Locale.US, fmt, xs.minOrNull() ?: 0.0)}, " +
                    "max ${String.format(Locale.US, fmt, xs.maxOrNull() ?: 0.0)}]"
            )
        }
        line("teamA %", metrics.map { it.teamAPct }, "%.1f")
        line("Aceptación %", metrics.map { it.acceptPct }, "%.1f")
        line("Aceptar neto / partida", metrics.map { it.acceptNetPerGame }, "%.3f")
        line("Pedir Mus %", metrics.map { it.musPct }, "%.1f")
        sb.appendLine("Interpretación: σ es el ruido inter-lote a este N. Un Δ entre dos")
        sb.appendLine("corridas que NO supere ~2σ es ruido, no señal de un cambio de IA.")
        return sb.toString()
    }

    private fun buildReport(s: Stats, games: Int): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        sb.appendLine("MusVisto — Simulación IA vs IA")
        sb.appendLine("Timestamp: $ts | Partidas: ${s.games} | Stalled: ${s.stalledGames}")
        sb.appendLine("=".repeat(56))
        sb.appendLine()
        sb.appendLine("RESULTADOS")
        sb.appendLine("- Victorias teamA: ${s.teamAWins} (${pct(s.teamAWins, s.games)})")
        sb.appendLine("- Victorias teamB: ${s.teamBWins} (${pct(s.teamBWins, s.games)})")
        val finished = s.teamAWins + s.teamBWins
        sb.appendLine("- Rondas/partida (media): " +
            if (finished == 0) "—" else String.format(Locale.US, "%.1f", s.totalRounds.toDouble() / finished))
        sb.appendLine("- Partidas con órdago: ${s.ordagoGames}")
        sb.appendLine()
        sb.appendLine("MUS / DESCARTE")
        val musTotal = s.musDecisions + s.noMusDecisions
        sb.appendLine("- Pedir Mus: ${s.musDecisions} (${pct(s.musDecisions, musTotal)}) | " +
            "Cortar (NoMus): ${s.noMusDecisions} (${pct(s.noMusDecisions, musTotal)})")
        sb.appendLine("- Ciclos de descarte / reparto: " +
            if (s.deals == 0) "—" else String.format(Locale.US, "%.2f", s.discardCycles.toDouble() / s.deals))
        sb.appendLine("- Delegación de corte #20: cede ${s.delegationFired} | " +
            "break ${s.delegationBreak} (fidelidad: 0 ⇒ la delegación NO se ejerce en el sim)")
        sb.appendLine()
        sb.appendLine("POR LANCE (envida / paso / quiero / no quiero / órdago / apoyo)")
        for (p in bettingLances) {
            val env = s.envido[p] ?: 0
            val avg = if (env == 0) "—" else String.format(Locale.US, "%.1f", (s.envidoAmt[p] ?: 0).toDouble() / env)
            sb.appendLine("- $p: envida $env (imp.medio $avg) | paso ${s.paso[p] ?: 0} | " +
                "quiero ${s.quiero[p] ?: 0} | noquiero ${s.noQuiero[p] ?: 0} | " +
                "órdago ${s.ordago[p] ?: 0} | apoyo ${s.supportFired[p] ?: 0}")
        }
        sb.appendLine()
        sb.appendLine("31 EN POSTRE ANTE ENVITE A JUEGO")
        sb.appendLine("- Casos: ${s.postre31Faced} | quiero ${s.postre31Quiero} " +
            "(${pct(s.postre31Quiero, s.postre31Faced)}) | noquiero ${s.postre31NoQuiero} " +
            "(${pct(s.postre31NoQuiero, s.postre31Faced)}) | subió ${s.postre31Subio}")
        sb.appendLine()
        sb.appendLine("CALIDAD DE DECISIONES (contra el ganador real del lance)")
        val accTotal = s.acceptWon + s.acceptLost
        sb.appendLine("- Aceptar 'quiero': gana ${s.acceptWon}/${accTotal} " +
            "(${pct(s.acceptWon, accTotal)}) | tantos netos: ${s.acceptNet}")
        // BREAKDOWN POR LANCE
        sb.appendLine("  Por lance:")
        for (p in bettingLances) {
            val won = s.acceptWonByPhase[p] ?: 0
            val lost = s.acceptLostByPhase[p] ?: 0
            val tot = won + lost
            val net = s.acceptNetByPhase[p] ?: 0
            sb.appendLine("    - $p: $won/$tot (${pct(won, tot)}) | netos $net")
        }
        // BREAKDOWN POR TAMAÑO DE ENVITE
        sb.appendLine("  Por tamaño de envite aceptado:")
        val allAmts = (s.acceptWonByAmt.keys + s.acceptLostByAmt.keys).sorted()
        for (amt in allAmts) {
            val won = s.acceptWonByAmt[amt] ?: 0
            val lost = s.acceptLostByAmt[amt] ?: 0
            val tot = won + lost
            val net = s.acceptNetByAmt[amt] ?: 0
            sb.appendLine("    - envite=$amt: $won/$tot (${pct(won, tot)}) | netos $net")
        }
        // BREAKDOWN POR POSICIÓN (rivales por delante en el lance)
        sb.appendLine("  Aceptar por posición (rivales por delante en el lance):")
        for (pos in (s.acceptWonByPos.keys + s.acceptLostByPos.keys).sorted()) {
            val won = s.acceptWonByPos[pos] ?: 0
            val lost = s.acceptLostByPos[pos] ?: 0
            val tot = won + lost
            sb.appendLine("    - rivales_delante=$pos: $won/$tot (${pct(won, tot)}) | " +
                "netos ${s.acceptNetByPos[pos] ?: 0}")
        }
        // BREAKDOWN POR MARCADOR
        sb.appendLine("  Aceptar por marcador:")
        for (k in scoreBuckets) {
            val won = s.acceptWonByScore[k] ?: 0
            val lost = s.acceptLostByScore[k] ?: 0
            val tot = won + lost
            if (tot == 0) continue
            sb.appendLine("    - $k: $won/$tot (${pct(won, tot)}) | netos ${s.acceptNetByScore[k] ?: 0}")
        }
        val envTotal = s.envidoWon + s.envidoLost
        sb.appendLine("- Envites abiertos que el equipo gana en showdown: " +
            "${s.envidoWon}/${envTotal} (${pct(s.envidoWon, envTotal)}) " +
            "(ojo: un farol que gana porque el rival pliega NO cuenta aquí; " +
            "% bajo puede ser farol legítimo)")
        sb.appendLine("  Abrir, % showdown por posición (rivales por delante):")
        for (pos in (s.envidoWonByPos.keys + s.envidoLostByPos.keys).sorted()) {
            val won = s.envidoWonByPos[pos] ?: 0
            val tot = won + (s.envidoLostByPos[pos] ?: 0)
            sb.appendLine("    - rivales_delante=$pos: $won/$tot (${pct(won, tot)})")
        }
        sb.appendLine("  Abrir, % showdown por marcador:")
        for (k in scoreBuckets) {
            val won = s.envidoWonByScore[k] ?: 0
            val tot = won + (s.envidoLostByScore[k] ?: 0)
            if (tot == 0) continue
            sb.appendLine("    - $k: $won/$tot (${pct(won, tot)})")
        }
        sb.appendLine()
        sb.appendLine("=".repeat(56))
        sb.appendLine("DIAGNÓSTICO (heurístico, en lenguaje claro)")
        sb.appendLine("=".repeat(56))
        diagnose(s).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /** Conclusiones automáticas: convierte números en avisos accionables. */
    private fun diagnose(s: Stats): List<String> {
        val out = mutableListOf<String>()
        val finished = s.teamAWins + s.teamBWins

        // Equilibrio de la partida (sesgo de posición/mano).
        if (finished >= 20) {
            val aWin = 100.0 * s.teamAWins / finished
            when {
                aWin in 42.0..58.0 -> out += "✅ Equilibrio de equipos sano (teamA ${pct(s.teamAWins, finished)})."
                else -> out += "⚠️ Desequilibrio de equipos (teamA ${pct(s.teamAWins, finished)}). " +
                    "Posible sesgo por posición/mano o por simetría de la IA. Relacionado: backlog #2 (posición)."
            }
        } else {
            out += "ℹ️ Pocas partidas terminadas ($finished) para juzgar equilibrio; sube -Dmusvisto.games."
        }

        // Timidez / pasividad (backlog #1). Dos señales:
        //  - tasa de aceptación: cuando hay envite, ¿se quiere o se pliega?
        //  - tasa de paso: ¿se abre alguna vez o casi todo es paso?
        val totalEnvido = bettingLances.sumOf { s.envido[it] ?: 0 }
        val totalPaso = bettingLances.sumOf { s.paso[it] ?: 0 }
        val totalQuiero = bettingLances.sumOf { s.quiero[it] ?: 0 }
        val totalNoQuiero = bettingLances.sumOf { s.noQuiero[it] ?: 0 }
        val totalOrdago = bettingLances.sumOf { s.ordago[it] ?: 0 }
        val faced = totalQuiero + totalNoQuiero
        val acceptRate = if (faced == 0) 0.0 else 100.0 * totalQuiero / faced
        val passRate = if (totalEnvido + totalPaso == 0) 0.0
            else 100.0 * totalPaso / (totalEnvido + totalPaso)

        when {
            faced < 30 ->
                out += "ℹ️ Pocos envites afrontados ($faced) para juzgar pasividad; sube -Dmusvisto.games."
            acceptRate < 20.0 ->
                out += "⚠️ IA demasiado tímida: ante un envite SOLO acepta el " +
                    "${"%.0f".format(acceptRate)}% (quiere $totalQuiero / no quiere $totalNoQuiero). " +
                    "Casi ningún lance se disputa. Backlog #1: umbrales de querer/envidar altos."
            acceptRate < 35.0 ->
                out += "⚠️ Aceptación baja (${"%.0f".format(acceptRate)}% de los envites). " +
                    "Roza la pasividad; revisar umbral de 'quiero'. Backlog #1."
            else ->
                out += "✅ Aceptación de envites razonable (${"%.0f".format(acceptRate)}%)."
        }
        if (passRate > 85.0 && totalEnvido + totalPaso >= 200)
            out += "⚠️ Se pasa el ${"%.0f".format(passRate)}% de las veces que se podría abrir: " +
                "la IA apenas inicia apuestas. Backlog #1 (umbral de envidar)."
        if (totalQuiero in 1..Int.MAX_VALUE && totalOrdago > totalQuiero)
            out += "⚠️ Apuestas bimodales: hay más órdagos ($totalOrdago) que 'quiero' " +
                "($totalQuiero). La IA o pliega o va a por todas; falta el envite medio jugado."

        // CALIDAD: ¿las aceptaciones tienen sentido? (vs ganador real del lance)
        val accTotal = s.acceptWon + s.acceptLost
        when {
            accTotal < 30 ->
                out += "ℹ️ Pocas aceptaciones ($accTotal) para juzgar su calidad; sube -Dmusvisto.games."
            else -> {
                val wr = 100.0 * s.acceptWon / accTotal
                when {
                    wr < 40.0 ->
                        out += "⚠️ Las aceptaciones NO compensan: al decir 'quiero' solo gana el " +
                            "${"%.0f".format(wr)}% de los lances (neto ${s.acceptNet} tantos). " +
                            "Acepta demasiado flojo: subir más la aceptación sería contraproducente."
                    s.acceptNet < 0 ->
                        out += "⚠️ Aceptar es deficitario: gana ${"%.0f".format(wr)}% pero el neto " +
                            "es ${s.acceptNet} tantos (pierde más de lo que gana apostando)."
                    wr > 70.0 ->
                        out += "ℹ️ Aceptaciones muy ganadoras (${"%.0f".format(wr)}%, neto " +
                            "+${s.acceptNet}): probablemente AÚN demasiado selectiva (deja de querer " +
                            "lances rentables). Hay margen para aceptar más."
                    else ->
                        out += "✅ Las aceptaciones tienen sentido: gana ${"%.0f".format(wr)}% de los " +
                            "lances aceptados, neto +${s.acceptNet} tantos. Subir la aceptación fue sano."
                }
            }
        }

        // Rol de apoyo (#1/#4).
        val supTotal = bettingLances.sumOf { s.supportFired[it] ?: 0 }
        val betDecisions = totalEnvido + totalPaso +
            bettingLances.sumOf { (s.quiero[it] ?: 0) + (s.noQuiero[it] ?: 0) }
        val supRate = if (betDecisions == 0) 0.0 else 100.0 * supTotal / betDecisions
        when {
            supTotal == 0 ->
                out += "⚠️ El rol de APOYO (capitanía #1/#4) NO se disparó nunca. " +
                    "Quizá los umbrales son inalcanzables o las señas no llegan."
            supRate > 35 ->
                out += "⚠️ El rol de APOYO se dispara mucho (${"%.0f".format(supRate)}% de decisiones). " +
                    "Riesgo de IA demasiado pasiva (backlog #1/#4: calibrar SUPPORT_OWN_FLOOR)."
            else ->
                out += "✅ El rol de APOYO se activa de forma moderada (${"%.0f".format(supRate)}%). " +
                    "Parece razonable; confirmar en playtest que coordina sin pasividad."
        }

        // Delegación de corte #20 (fidelidad del sim, bug D).
        val delegTotal = s.delegationFired + s.delegationBreak
        if (delegTotal == 0)
            out += "⚠️ La delegación de corte #20 NO se ejerció nunca (cede 0 / break 0). " +
                "El 'primero' del equipo decide su Mus a ciegas: el sim NO mide la IA real " +
                "(bug de fidelidad D — startRound no rellena pendingGestures)."
        else
            out += "✅ La delegación de corte #20 se ejerce (cede ${s.delegationFired} / " +
                "break ${s.delegationBreak}): el primero cede el corte al capitán como en el juego real."

        // 31 en postre (anti-exploit).
        if (s.postre31Faced >= 10) {
            val q = 100.0 * s.postre31Quiero / s.postre31Faced
            when {
                q >= 99.0 -> out += "⚠️ Un 31 en postre QUIERE el ${"%.0f".format(q)}% ante envite a Juego: " +
                    "vuelve a ser determinista/explotable (regresión del fix)."
                s.postre31NoQuiero == 0 && s.postre31Subio == 0 ->
                    out += "⚠️ Un 31 en postre nunca rechaza ni sube; revisar variabilidad."
                else -> out += "✅ Un 31 en postre mezcla respuestas (quiere ${"%.0f".format(q)}%, " +
                    "no quiere ${pct(s.postre31NoQuiero, s.postre31Faced)}): no es farmeable de forma determinista."
            }
        } else {
            out += "ℹ️ Pocos casos de 31 en postre (${s.postre31Faced}) para concluir; sube -Dmusvisto.games."
        }

        // Inflación por rondas de Mus (backlog #6, aún no implementado).
        val cyclesPerDeal = if (s.deals == 0) 0.0 else s.discardCycles.toDouble() / s.deals
        out += "ℹ️ Ciclos de descarte por reparto: ${"%.2f".format(cyclesPerDeal)}. " +
            "Útil como línea base para el backlog #6 (inflación de campo)."

        if (s.stalledGames > 0)
            out += "⚠️ ${s.stalledGames} partidas se cortaron por límite de acciones " +
                "(posible bucle en la lógica de juego). Investigar."

        return out
    }

    // ===================================================================
    // FASE 2 — ARNÉS DE DECISIÓN AISLADA (-Dmusvisto.mode=decisions)
    // ===================================================================
    // Corpus FIJO de manos repartidas con Random(seed). Para cada reparto se
    // construye el estado de cada lance DIRECTAMENTE (sin jugar los lances
    // previos, que es lo que en el modo partida-completa hace divergir las
    // trayectorias) y se puntúa la decisión de makeDecision contra el showdown
    // real de esas 4 manos. Cada makeDecision recibe un AILogic con su propio
    // Random determinista (derivado de la semilla + un contador de escenario), de
    // modo que un cambio en el consumo de rng de UNA decisión no perturba a las
    // demás → a/b causal, monótono y de bajo ruido.

    private val betSizesProbe = listOf(2, 4, 8)

    private data class RespKey(val phase: GamePhase, val pos: Int, val bet: Int)
    private data class OpenKey(val phase: GamePhase, val pos: Int)

    private class DecisionStats {
        var dealsEvaluated = 0
        var respDecisions = 0
        var openDecisions = 0

        // RESPUESTA a un envite de tamaño X, puntuada ex-post contra el showdown:
        //   jugar (Quiero/Envido/Órdago) → +X si gano el lance / −X si lo pierdo
        //   plegar (NoQuiero)            → −1 (pointsIfRejected del primer envite)
        // óptimo ex-post = max(jugar, plegar); regret = óptimo − elegido (≥0).
        val respCount = HashMap<RespKey, Int>()
        val respPlays = HashMap<RespKey, Int>()
        val respCorrect = HashMap<RespKey, Int>() // eligió el lado EV-óptimo
        val respRegret = HashMap<RespKey, Int>()   // tantos dejados en la mesa
        val respNet = HashMap<RespKey, Int>()      // EV realizado de lo elegido
        val respPlayWon = HashMap<RespKey, Int>()  // de lo jugado, ganó el showdown

        // APERTURA (sin envite): matriz de selección abrir|pasar × gana|pierde el
        // showdown. SIN número de EV: el valor de abrir depende del modelo de
        // plegado del rival (punto ciego del sim simétrico) → se reporta la
        // selección, no se finge un EV.
        val openOpenWin = HashMap<OpenKey, Int>()
        val openOpenLose = HashMap<OpenKey, Int>()
        val openCheckWin = HashMap<OpenKey, Int>()
        val openCheckLose = HashMap<OpenKey, Int>()
    }

    private fun <K> MutableMap<K, Int>.bump(k: K, v: Int = 1) { this[k] = (this[k] ?: 0) + v }

    private fun runDecisionHarness(scenarios: Int, batches: Int): String {
        val st = DecisionStats()
        // Foto de los agregados globales de RESPUESTA en cada frontera de lote
        // (semillas disjuntas) → banda de ruido del arnés (la prueba anti-caos).
        fun snap() = intArrayOf(
            st.respCount.values.sum(),
            st.respCorrect.values.sum(),
            st.respRegret.values.sum(),
            st.respNet.values.sum()
        )
        val snaps = mutableListOf(snap())
        for (b in 0 until batches) {
            val base = b.toLong() * scenarios
            for (i in 0 until scenarios) evalDeal(base + i, st)
            snaps += snap()
        }
        val perBatch = (1..batches).map { idx ->
            val p = snaps[idx - 1]; val c = snaps[idx]
            val dn = c[0] - p[0]
            val acc = if (dn == 0) 0.0 else 100.0 * (c[1] - p[1]) / dn
            val reg = if (dn == 0) 0.0 else (c[2] - p[2]).toDouble() / dn
            acc to reg
        }
        return buildDecisionReport(st, scenarios, batches, perBatch)
    }

    private fun evalDeal(seed: Long, st: DecisionStats) {
        val rng = Random(seed)
        val gameLogic = MusGameLogic(rng)
        val roster = players()
        // Alternar mano por reparto para equilibrar la distribución de posiciones.
        val manoId = if (seed % 2L == 0L) roster[0].id else roster[1].id
        val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, _) = gameLogic.dealCards(roster, deck, manoId)
        st.dealsEvaluated++

        // Señas conocidas: mirror de startRound (prob 0.90). En un lance real ya se
        // han mostrado al llegar a GRANDE; mismo plan para todos los lances.
        val known = mutableMapOf<String, ActiveGestureInfo>()
        val pending = mutableMapOf<String, Int>()
        for (p in dealt) {
            val g = determineGesture(p, gameLogic) ?: continue
            if (rng.nextFloat() < 0.90f) { pending[p.id] = g; known[p.id] = ActiveGestureInfo(p.id, g) }
        }

        val baseState = GameState(
            players = dealt,
            score = mapOf("teamA" to 0, "teamB" to 0),
            manoPlayerId = manoId,
            knownGestures = known,
            pendingGestures = pending
        )

        // rng PROPIO por decisión: garantiza independencia/reproducibilidad y que un
        // cambio de lógica en una decisión no arrastre a otra vía estado de rng.
        var sc = 0
        fun freshAi() = AILogic(gameLogic, Random(seed * 10_000L + sc++))

        for (lance in bettingLances) {
            val inLance = playersInLanceFor(lance, dealt, gameLogic) ?: continue
            val winnerTeam = lanceWinnerTeam(lance, baseState, gameLogic) ?: continue
            val isPunto = lance == GamePhase.JUEGO &&
                dealt.none { gameLogic.getHandJuegoValue(it.hand) >= 31 }
            val inLanceIds = inLance.map { it.id }.toSet()
            val ordered = gameLogic.getTurnOrderedPlayers(dealt, manoId).filter { it.id in inLanceIds }

            for (ai in inLance) {
                val myIdx = ordered.indexOfFirst { it.id == ai.id }
                val pos = ordered.take(myIdx).count { it.team != ai.team } // rivales por delante
                val mineWins = ai.team == winnerTeam
                val lanceState = baseState.copy(
                    gamePhase = lance,
                    isPuntoPhase = isPunto,
                    playersInLance = inLanceIds,
                    currentTurnPlayerId = ai.id,
                    availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
                )

                // APERTURA (sin envite activo).
                val openDec = freshAi().makeDecision(lanceState, ai)
                recordOpen(st, OpenKey(lance, pos), openDec.action, mineWins)
                st.openDecisions++

                // RESPUESTA a un envite SINTÉTICO de tamaño X de un rival del lance.
                // Inyectarlo (en vez de esperar a que surja de una IA gemela tímida)
                // desacopla el 'aceptar' del sesgo "el envite solo viene de manos
                // fuertes" → menos punto ciego que el modo partida-completa.
                val bettor = ordered.firstOrNull { it.team != ai.team } ?: continue
                for (x in betSizesProbe) {
                    val betState = lanceState.copy(
                        currentBet = BetInfo(
                            amount = x,
                            bettingPlayerId = bettor.id,
                            respondingPlayerId = ai.id,
                            pointsIfRejected = 1
                        )
                    )
                    recordResp(st, RespKey(lance, pos, x), freshAi().makeDecision(betState, ai).action, mineWins, x)
                    st.respDecisions++
                }
            }
        }
    }

    /**
     * Jugadores que participan en el lance, o null si el lance NO se disputaría
     * (el motor lo saltaría → no hay decisión). GRANDE/CHICA: los 4. PARES/JUEGO:
     * solo si AL MENOS DOS equipos tienen jugada (espejo de beginDeclarationBetting);
     * JUEGO con nadie ≥31 = Punto (los 4 juegan).
     */
    private fun playersInLanceFor(lance: GamePhase, dealt: List<Player>, gl: MusGameLogic): List<Player>? =
        when (lance) {
            GamePhase.GRANDE, GamePhase.CHICA -> dealt
            GamePhase.PARES -> {
                val withPares = dealt.filter { gl.getHandPares(it.hand).strength > 0 }
                if (withPares.map { it.team }.distinct().size >= 2) withPares else null
            }
            GamePhase.JUEGO -> {
                val withJuego = dealt.filter { gl.getHandJuegoValue(it.hand) >= 31 }
                when {
                    withJuego.map { it.team }.distinct().size >= 2 -> withJuego
                    withJuego.isEmpty() -> dealt          // Punto: todos juegan
                    else -> null                          // un solo equipo con Juego → saltado
                }
            }
            else -> null
        }

    private fun lanceWinnerTeam(lance: GamePhase, state: GameState, gl: MusGameLogic): String? =
        when (lance) {
            GamePhase.GRANDE -> gl.getGrandeWinner(state)?.team
            GamePhase.CHICA -> gl.getChicaWinner(state)?.team
            GamePhase.PARES -> gl.getParesWinner(state)?.team
            GamePhase.JUEGO -> gl.getJuegoWinner(state)?.team
            else -> null
        }

    private fun recordResp(st: DecisionStats, key: RespKey, action: GameAction, mineWins: Boolean, x: Int) {
        val played = action is GameAction.Quiero || action is GameAction.Envido || action is GameAction.Órdago
        val evPlay = if (mineWins) x else -x
        val evFold = -1
        val chosen = if (played) evPlay else evFold
        val optimal = maxOf(evPlay, evFold)
        st.respCount.bump(key)
        if (played) st.respPlays.bump(key)
        if (chosen == optimal) st.respCorrect.bump(key)
        st.respRegret.bump(key, optimal - chosen)
        st.respNet.bump(key, chosen)
        if (played && mineWins) st.respPlayWon.bump(key)
    }

    private fun recordOpen(st: DecisionStats, key: OpenKey, action: GameAction, mineWins: Boolean) {
        val opened = action is GameAction.Envido || action is GameAction.Órdago
        when {
            opened && mineWins -> st.openOpenWin.bump(key)
            opened && !mineWins -> st.openOpenLose.bump(key)
            !opened && mineWins -> st.openCheckWin.bump(key)
            else -> st.openCheckLose.bump(key)
        }
    }

    private fun fmt1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun fmt3(x: Double) = String.format(Locale.US, "%.3f", x)

    private fun buildDecisionReport(
        st: DecisionStats,
        scenarios: Int,
        batches: Int,
        perBatch: List<Pair<Double, Double>>
    ): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        sb.appendLine("MusVisto — Arnés de decisión aislada (Fase 2)")
        sb.appendLine("Timestamp: $ts | Repartos: ${st.dealsEvaluated} | " +
            "Decisiones: respuesta ${st.respDecisions}, apertura ${st.openDecisions}")
        sb.appendLine("Corpus FIJO por semilla: el a/b mide SOLO el cambio de decisión.")
        sb.appendLine("=".repeat(64))
        sb.appendLine()

        // ---- RESPUESTA ----
        val rCount = st.respCount.values.sum()
        sb.appendLine("RESPUESTA A ENVITE (puntuada ex-post vs showdown real)")
        sb.appendLine("  jugar = +X si gano / −X si pierdo | plegar = −1 | regret = óptimo − elegido")
        sb.appendLine("- GLOBAL: acierto ${pct(st.respCorrect.values.sum(), rCount)} (lado EV-óptimo) | " +
            "regret/decisión ${fmt3(st.respRegret.values.sum().toDouble() / maxOf(rCount, 1))} | " +
            "juega ${pct(st.respPlays.values.sum(), rCount)} | neto ${st.respNet.values.sum()}")
        sb.appendLine()
        sb.appendLine(String.format(Locale.US, "  %-7s %4s %4s %8s %9s %10s %8s",
            "lance", "pos", "bet", "n", "acierto", "regret/d", "juega%"))
        for (lance in bettingLances) for (pos in 0..2) for (x in betSizesProbe) {
            val k = RespKey(lance, pos, x)
            val n = st.respCount[k] ?: 0
            if (n == 0) continue
            sb.appendLine(String.format(Locale.US, "  %-7s %4d %4d %8d %9s %10s %8s",
                lance.name.take(7), pos, x, n,
                pct(st.respCorrect[k] ?: 0, n),
                fmt3((st.respRegret[k] ?: 0).toDouble() / n),
                pct(st.respPlays[k] ?: 0, n)))
        }
        sb.appendLine()
        sb.appendLine("  Resumen por lance (suma de posición y tamaño):")
        for (lance in bettingLances) {
            val keys = st.respCount.keys.filter { it.phase == lance }
            val n = keys.sumOf { st.respCount[it] ?: 0 }
            if (n == 0) continue
            val plays = keys.sumOf { st.respPlays[it] ?: 0 }
            sb.appendLine("    - ${lance.name}: n=$n | acierto ${pct(keys.sumOf { st.respCorrect[it] ?: 0 }, n)} | " +
                "regret/d ${fmt3(keys.sumOf { st.respRegret[it] ?: 0 }.toDouble() / n)} | " +
                "de lo jugado gana ${pct(keys.sumOf { st.respPlayWon[it] ?: 0 }, plays)} showdown | " +
                "neto ${keys.sumOf { st.respNet[it] ?: 0 }}")
        }
        sb.appendLine()

        // ---- APERTURA ----
        sb.appendLine("APERTURA (sin envite) — SELECCIÓN, no EV")
        sb.appendLine("  (el EV de abrir depende del plegado del rival: punto ciego del sim simétrico)")
        sb.appendLine(String.format(Locale.US, "  %-7s %4s %8s %8s %12s %12s",
            "lance", "pos", "n", "abre%", "%sd abierto", "%sd pasado"))
        for (lance in bettingLances) for (pos in 0..2) {
            val k = OpenKey(lance, pos)
            val ow = st.openOpenWin[k] ?: 0; val ol = st.openOpenLose[k] ?: 0
            val cw = st.openCheckWin[k] ?: 0; val cl = st.openCheckLose[k] ?: 0
            val n = ow + ol + cw + cl
            if (n == 0) continue
            sb.appendLine(String.format(Locale.US, "  %-7s %4d %8d %8s %12s %12s",
                lance.name.take(7), pos, n, pct(ow + ol, n), pct(ow, ow + ol), pct(cw, cw + cl)))
        }
        sb.appendLine("  Lectura: '%sd abierto' = de lo que abre, cuánto gana el showdown (alto = abre valor / ")
        sb.appendLine("  bajo = farol o sobre-apertura). '%sd pasado' alto = valor desperdiciado al pasar.")
        sb.appendLine()

        // ---- RUIDO DEL ARNÉS (prueba anti-caos) ----
        if (batches >= 2) {
            sb.appendLine("=".repeat(64))
            sb.appendLine("RUIDO DEL ARNÉS ($batches lotes de $scenarios repartos, semillas disjuntas)")
            sb.appendLine("=".repeat(64))
            val accs = perBatch.map { it.first }
            val regs = perBatch.map { it.second }
            val (am, asd) = meanStd(accs)
            val (rm, rsd) = meanStd(regs)
            sb.appendLine("- Acierto respuesta: ${fmt1(am)}% ± ${fmt1(asd)}%  " +
                "[min ${fmt1(accs.minOrNull() ?: 0.0)}, max ${fmt1(accs.maxOrNull() ?: 0.0)}]")
            sb.appendLine("- Regret/decisión:   ${fmt3(rm)} ± ${fmt3(rsd)}  " +
                "[min ${fmt3(regs.minOrNull() ?: 0.0)}, max ${fmt3(regs.maxOrNull() ?: 0.0)}]")
            sb.appendLine("Contraste: en el modo partida-completa el accept-net tiene σ≈27% de la media")
            sb.appendLine("(caos de trayectoria). Una σ pequeña aquí confirma que el arnés aísla la decisión.")
            sb.appendLine()
        }

        // ---- DIAGNÓSTICO ----
        sb.appendLine("=".repeat(64))
        sb.appendLine("DIAGNÓSTICO")
        sb.appendLine("=".repeat(64))
        diagnoseDecisions(st).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    private fun diagnoseDecisions(st: DecisionStats): List<String> {
        val out = mutableListOf<String>()
        val rCount = st.respCount.values.sum()
        if (rCount < 100) {
            out += "ℹ️ Pocas decisiones de respuesta ($rCount) para concluir; sube -Dmusvisto.scenarios."
            return out
        }
        val acc = 100.0 * st.respCorrect.values.sum() / rCount
        val reg = st.respRegret.values.sum().toDouble() / rCount
        val playRate = 100.0 * st.respPlays.values.sum() / rCount
        out += "Respuesta: acierta el lado EV-óptimo el ${fmt1(acc)}% (regret medio ${fmt3(reg)} tantos/decisión)."
        when {
            playRate < 15.0 -> out += "⚠️ Juega solo el ${fmt1(playRate)}% de los envites afrontados: muy plegadora " +
                "(coherente con la timidez del modo partida-completa). Ver si el regret viene de PLEGAR ganadores."
            playRate > 60.0 -> out += "⚠️ Juega el ${fmt1(playRate)}% de los envites: poco selectiva."
            else -> out += "✅ Tasa de juego intermedia (${fmt1(playRate)}%)."
        }
        out += "Regret por lance (mayor = peores decisiones de respuesta en ese lance):"
        bettingLances
            .map { l -> l to st.respRegret.keys.filter { it.phase == l }.sumOf { st.respRegret[it] ?: 0 } }
            .sortedByDescending { it.second }
            .forEach { (l, r) ->
                val n = st.respCount.keys.filter { it.phase == l }.sumOf { st.respCount[it] ?: 0 }
                if (n > 0) out += "  - ${l.name}: regret/d ${fmt3(r.toDouble() / n)} (n=$n)"
            }
        out += "Regret por posición (rivales por delante; mayor exposición = más fácil decidir mal):"
        for (pos in 0..2) {
            val n = st.respCount.keys.filter { it.pos == pos }.sumOf { st.respCount[it] ?: 0 }
            if (n == 0) continue
            val r = st.respRegret.keys.filter { it.pos == pos }.sumOf { st.respRegret[it] ?: 0 }
            out += "  - rivales_delante=$pos: regret/d ${fmt3(r.toDouble() / n)} (n=$n)"
        }
        out += "Nota: el showdown es ex-post (no modela el farol del humano). El arnés inyecta el envite, " +
            "así que el 'aceptar' está MENOS sesgado que en partida-completa, pero la apertura sigue " +
            "siendo selección, no EV (ver matriz arriba)."
        return out
    }

    private fun writeReport(report: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val candidates = listOf(File("docs/ai_logs"), File("../docs/ai_logs"))
        val dir = candidates.firstOrNull { it.parentFile?.exists() == true } ?: candidates.first()
        dir.mkdirs()
        val out = File(dir, "sim_${ts}_summary.txt")
        out.writeText(report)
        println("Resumen escrito en: ${out.absolutePath}")
    }
}
