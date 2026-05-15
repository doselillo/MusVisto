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
        val stats = Stats()

        repeat(games) { i -> playOneGame(seed = i.toLong(), stats = stats) }

        val report = buildReport(stats, games)
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
        var manoId = roster.first().id

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

        // Gestos: cada IA con compañero IA, 70% de pasar seña (mirror de
        // GameViewModel.triggerAiGestures + determineGesture).
        val known = mutableMapOf<String, ActiveGestureInfo>()
        for (p in dealt) {
            if (rng.nextFloat() < 0.70f) {
                val g = determineGesture(p, gameLogic)
                if (g != null) known[p.id] = ActiveGestureInfo(p.id, g)
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
                when (val a = decision.action) {
                    is GameAction.Quiero -> {
                        val amt = state.currentBet?.amount ?: 0
                        if (mineWins) { stats.acceptWon++; stats.acceptNet += amt }
                        else { stats.acceptLost++; stats.acceptNet -= amt }
                    }
                    is GameAction.Envido -> {
                        if (mineWins) stats.envidoWon++ else stats.envidoLost++
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

    // --- Informe + Diagnóstico ---

    private fun pct(n: Int, total: Int): String =
        if (total == 0) "—" else String.format(Locale.US, "%.1f%%", 100.0 * n / total)

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
        val envTotal = s.envidoWon + s.envidoLost
        sb.appendLine("- Envites abiertos que el equipo gana en showdown: " +
            "${s.envidoWon}/${envTotal} (${pct(s.envidoWon, envTotal)}) " +
            "(ojo: un farol que gana porque el rival pliega NO cuenta aquí; " +
            "% bajo puede ser farol legítimo)")
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
