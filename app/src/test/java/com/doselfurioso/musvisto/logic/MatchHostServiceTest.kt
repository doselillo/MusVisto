package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameCommandCodec
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.toCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.random.Random

/**
 * Transporte en memoria (síncrono) para tests: enruta comandos y vistas sin red.
 * Mapea el comportamiento de [MatchTransport] que en producción daría Firebase RTDB.
 */
private class FakeMatchTransport : MatchTransport {
    private var commandHandler: ((String, GameCommand) -> Unit)? = null
    private val viewObservers = mutableMapOf<String, (GameState) -> Unit>()
    val lastView = mutableMapOf<String, GameState>()

    override fun publishView(seatId: String, view: GameState) {
        lastView[seatId] = view
        viewObservers[seatId]?.invoke(view)
    }

    override fun observeCommands(onCommand: (String, GameCommand) -> Unit) {
        commandHandler = onCommand
    }

    override fun sendCommand(seatId: String, command: GameCommand) {
        commandHandler?.invoke(seatId, command)
    }

    override fun observeView(seatId: String, onView: (GameState) -> Unit) {
        viewObservers[seatId] = onView
    }
}

/** Bucle host↔clientes completo sobre un transporte en memoria. */
class MatchHostServiceTest {

    private val seatIds = listOf("p1", "p2", "p3", "p4")

    private fun players() = listOf(
        Player(id = "p1", name = "A1", avatarResId = 0, team = "teamA"),
        Player(id = "p2", name = "B1", avatarResId = 0, isAi = true, team = "teamB"),
        Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
        Player(id = "p4", name = "B2", avatarResId = 0, isAi = true, team = "teamB")
    )

    private fun dealtMusState(): GameState {
        val logic = MusGameLogic(Random(0))
        val deck = logic.shuffleDeck(logic.createDeck())
        val (dealt, rest) = logic.dealCards(players(), deck, "p1")
        return GameState(
            players = dealt, deck = rest, gamePhase = GamePhase.MUS,
            currentTurnPlayerId = "p1", manoPlayerId = "p1",
            playersInLance = seatIds.toSet(),
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    @Test
    fun `al arrancar, cada cliente recibe su vista redactada`() {
        val transport = FakeMatchTransport()
        val service = MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds, scope = CoroutineScope(Dispatchers.Unconfined))

        val p1View = capture(transport, "p1")
        val p2View = capture(transport, "p2")
        service.start()

        // p1 ve su mano (4) y NO la de los demás; el mazo nunca viaja.
        assertEquals(4, p1View().players.first { it.id == "p1" }.hand.size)
        assertTrue(p1View().players.first { it.id == "p2" }.hand.isEmpty())
        assertTrue(p1View().deck.isEmpty())
        // p2 ve la suya y no la de p1.
        assertEquals(4, p2View().players.first { it.id == "p2" }.hand.size)
        assertTrue(p2View().players.first { it.id == "p1" }.hand.isEmpty())
    }

    @Test
    fun `un comando de un cliente avanza el estado y re-publica las vistas`() {
        val transport = FakeMatchTransport()
        val service = MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds, scope = CoroutineScope(Dispatchers.Unconfined))
        val p2View = capture(transport, "p2")
        service.start()

        // El cliente p1 pide Mus a través del transporte (como lo haría por red).
        transport.sendCommand("p1", GameCommand.Mus)

        // p2 recibe una vista NUEVA con el estado avanzado, y sigue sin ver manos ajenas.
        assertNotNull(p2View())
        assertTrue("p1 pidió Mus → ya no es su turno", p2View().currentTurnPlayerId != "p1")
        assertTrue(p2View().players.first { it.id == "p1" }.hand.isEmpty())
    }

    @Test
    fun `el comando viaja por el codec sin cambiar el resultado`() {
        val transport = FakeMatchTransport()
        MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds, scope = CoroutineScope(Dispatchers.Unconfined)).start()

        val decoded = GameCommandCodec.decode(GameCommandCodec.encode(GameCommand.Mus))
        transport.sendCommand("p1", decoded)

        assertEquals(GamePhase.MUS, transport.lastView.getValue("p3").gamePhase)
        assertTrue(transport.lastView.getValue("p3").currentTurnPlayerId != "p1")
    }

    @Test
    fun `con AiSeatDriver una mesa de solo IA auto-juega la ronda hasta resolverla`() {
        val gameLogic = MusGameLogic(Random(0))
        val allAi = players().map { it.copy(isAi = true) }
        val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, rest) = gameLogic.dealCards(allAi, deck, "p1")
        val state = GameState(
            players = dealt, deck = rest, gamePhase = GamePhase.MUS,
            currentTurnPlayerId = "p1", manoPlayerId = "p1",
            playersInLance = seatIds.toSet(),
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
        val host = MatchHost(gameLogic, state)
        // Un AILogic por asiento, compartiendo rng (interleave determinista, como en producción).
        val rng = Random(0)
        val aiLogics = seatIds.associateWith { AILogic(gameLogic, rng, AIProfile()) }
        val transport = FakeMatchTransport()

        MatchHostService(host, transport, seatIds, AiSeatDriver(aiLogics), scope = CoroutineScope(Dispatchers.Unconfined)).start()

        // La IA jugó sola la ronda entera (Mus → lances → resolución) por el bucle del host.
        val phase = host.authoritativeState.gamePhase
        assertTrue(
            "Una mesa de solo IA debe auto-resolver la ronda; quedó en $phase",
            phase == GamePhase.ROUND_OVER || phase == GamePhase.GAME_OVER
        )
    }

    @Test
    fun `mesa mixta (humanos via transporte + IA host) recorre manos sin congelarse`() {
        // Reproduce el reporte de playtest (el turno de la IA se "congela"). Los
        // asientos "humanos" (p1,p2) DECIDEN como la IA —para explorar TODO el
        // espacio: envido, órdago, quiero, descarte— y mandan su comando por el
        // transporte, justo como un cliente de red. Las IA (p3,p4) las conduce el
        // host. Si tras el burst seguimos en un asiento de IA, está atascado → falla
        // con el diagnóstico (fase, acciones legales, decisión de la IA y su command).
        for (seed in 0 until 300) {
            val gameLogic = MusGameLogic(Random(seed.toLong()))
            val tablePlayers = listOf(
                Player(id = "p1", name = "H1", avatarResId = 0, team = "teamA"),
                Player(id = "p2", name = "H2", avatarResId = 0, team = "teamB"),
                Player(id = "p3", name = "A1", avatarResId = 0, isAi = true, team = "teamA"),
                Player(id = "p4", name = "A2", avatarResId = 0, isAi = true, team = "teamB")
            )
            val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
            val (dealt, rest) = gameLogic.dealCards(tablePlayers, deck, "p1")
            val state = GameState(
                players = dealt, deck = rest, gamePhase = GamePhase.MUS,
                currentTurnPlayerId = "p1", manoPlayerId = "p1",
                playersInLance = seatIds.toSet(),
                availableActions = listOf(GameAction.Mus, GameAction.NoMus)
            )
            val host = MatchHost(gameLogic, state)
            // Un cerebro por asiento con arquetipos REALES rotados (como asigna el
            // lobby online), rng propio = determinista por semilla.
            val profiles = listOf(AIProfile.EQUILIBRADO, AIProfile.AGRESIVO, AIProfile.CONSERVADOR, AIProfile.FAROLERO)
            val brains = seatIds.mapIndexed { i, id ->
                id to AILogic(gameLogic, Random(seed * 10L + i), profiles[(seed + i) % profiles.size])
            }.toMap()
            val aiDriver = AiSeatDriver(brains.filterKeys { it == "p3" || it == "p4" })
            val transport = FakeMatchTransport()
            MatchHostService(host, transport, seatIds, aiDriver, scope = CoroutineScope(Dispatchers.Unconfined)).start()

            var steps = 0
            while (steps++ < 500) {
                val s = host.authoritativeState
                if (s.gamePhase == GamePhase.ROUND_OVER || s.gamePhase == GamePhase.GAME_OVER) break
                val turn = s.currentTurnPlayerId!!
                val player = s.players.first { it.id == turn }
                val decision = brains.getValue(turn).makeDecision(s, player)
                if (turn == "p3" || turn == "p4") {
                    throw AssertionError(
                        "CONGELADO en IA $turn (seed=$seed): fase=${s.gamePhase}, " +
                            "acciones=${s.availableActions.map { it::class.simpleName }}, " +
                            "decisión=${decision.action}, command=${decision.action.toCommand(decision.cardsToDiscard)}, " +
                            "currentBet=${s.currentBet}"
                    )
                }
                val command = decision.action.toCommand(decision.cardsToDiscard) ?: throw AssertionError(
                    "Humano $turn: decisión ${decision.action} no mapea a comando (seed=$seed, fase=${s.gamePhase})"
                )
                transport.sendCommand(turn, command)
            }
            assertTrue(
                "Mano sin resolver (¿atasco?) seed=$seed: fase=${host.authoritativeState.gamePhase}, turno=${host.authoritativeState.currentTurnPlayerId}",
                host.authoritativeState.gamePhase == GamePhase.ROUND_OVER || host.authoritativeState.gamePhase == GamePhase.GAME_OVER
            )
        }
    }

    @Test
    fun `transicion de ronda - mesa de solo IA encadena rondas hasta ganar el chico`() {
        val gameLogic = MusGameLogic(Random(0))
        val allAi = players().map { it.copy(isAi = true) }
        val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, rest) = gameLogic.dealCards(allAi, deck, "p1")
        val state = GameState(
            players = dealt, deck = rest, gamePhase = GamePhase.MUS,
            currentTurnPlayerId = "p1", manoPlayerId = "p1",
            playersInLance = seatIds.toSet(),
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
        val host = MatchHost(gameLogic, state)
        val rng = Random(0)
        val aiLogics = seatIds.associateWith { AILogic(gameLogic, rng, AIProfile()) }
        val transport = FakeMatchTransport()

        // MatchPacing por defecto (turnMs=0, roundOverMs=0) → la mesa juega la PARTIDA
        // ENTERA (varias rondas) de forma síncrona dentro de start().
        MatchHostService(
            host, transport, seatIds, AiSeatDriver(aiLogics),
            scope = CoroutineScope(Dispatchers.Unconfined)
        ).start()

        val finalState = host.authoritativeState
        val scoreToWin = finalState.settings.pointsPerChico
        // Alcanzar pointsPerChico (40) exige ENCADENAR muchas rondas (cada una da
        // pocos tantos) → demuestra que la transición ROUND_OVER→reparto se repitió.
        assertEquals("La partida debe terminar al ganar el chico", GamePhase.GAME_OVER, finalState.gamePhase)
        assertNotNull("Debe registrarse el ganador del chico", finalState.winningTeam)
        val topScore = (finalState.score["teamA"] ?: 0).coerceAtLeast(finalState.score["teamB"] ?: 0)
        assertTrue("El ganador debe haber alcanzado el chico ($topScore >= $scoreToWin)", topScore >= scoreToWin)
        // Y el último estado publicado a cada cliente debe reflejar el fin de partida.
        assertEquals(GamePhase.GAME_OVER, transport.lastView.getValue("p1").gamePhase)
    }

    @Test
    fun `con humano en mesa, el host espera Continuar para repartir la siguiente ronda`() {
        val gameLogic = MusGameLogic(Random(0))
        // p1 HUMANO, resto IA. Conducimos una ronda completa (en el test, a p1 también
        // lo mueve un cerebro) hasta un ROUND_OVER realista, SIN el servicio.
        val table = listOf(
            Player(id = "p1", name = "H", avatarResId = 0, team = "teamA"),
            Player(id = "p2", name = "A1", avatarResId = 0, isAi = true, team = "teamB"),
            Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
            Player(id = "p4", name = "A3", avatarResId = 0, isAi = true, team = "teamB")
        )
        val deck = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, rest) = gameLogic.dealCards(table, deck, "p1")
        val host = MatchHost(
            gameLogic,
            GameState(
                players = dealt, deck = rest, gamePhase = GamePhase.MUS,
                currentTurnPlayerId = "p1", manoPlayerId = "p1",
                playersInLance = seatIds.toSet(),
                availableActions = listOf(GameAction.Mus, GameAction.NoMus)
            )
        )
        val brains = seatIds.associateWith { AILogic(gameLogic, Random(0), AIProfile()) }
        var guard = 0
        while (host.authoritativeState.gamePhase != GamePhase.ROUND_OVER &&
            host.authoritativeState.gamePhase != GamePhase.GAME_OVER && guard++ < 500
        ) {
            if (host.resolveSystemPhaseIfAny()) continue
            val s = host.authoritativeState
            val turn = s.currentTurnPlayerId!!
            val d = brains.getValue(turn).makeDecision(s, s.players.first { it.id == turn })
            host.submitCommand(turn, d.action.toCommand(d.cardsToDiscard)!!)
        }
        assertEquals("el warmup debe acabar en fin de ronda", GamePhase.ROUND_OVER, host.authoritativeState.gamePhase)

        val transport = FakeMatchTransport()
        val aiDriver = AiSeatDriver(brains.filterKeys { it != "p1" })
        MatchHostService(
            host, transport, seatIds, aiDriver,
            scope = CoroutineScope(Dispatchers.Unconfined)
        ).start()

        // Con un humano (p1) en mesa, el host PUNTÚA pero NO reparte: espera "Continuar".
        assertEquals(GamePhase.ROUND_OVER, host.authoritativeState.gamePhase)
        assertNotNull("la ronda debe quedar puntuada (desglose)", host.authoritativeState.scoreBreakdown)

        // El humano pulsa Continuar → el host reparte la siguiente (mano rota p1→p4).
        transport.sendCommand("p1", GameCommand.Continue)
        val after = host.authoritativeState
        assertTrue("ya no está en fin de ronda tras Continuar", after.gamePhase != GamePhase.ROUND_OVER)
        assertEquals("la mano rota al siguiente jugador", "p4", after.manoPlayerId)
    }

    /** Suscribe un observador y devuelve un getter de la última vista recibida. */
    private fun capture(transport: FakeMatchTransport, seatId: String): () -> GameState {
        var latest: GameState? = null
        transport.observeView(seatId) { latest = it }
        return { latest ?: error("Sin vista para $seatId") }
    }
}
