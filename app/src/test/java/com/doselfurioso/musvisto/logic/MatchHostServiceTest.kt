package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameCommandCodec
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        val service = MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds)

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
        val service = MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds)
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
        MatchHostService(MatchHost(MusGameLogic(Random(0)), dealtMusState()), transport, seatIds).start()

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

        MatchHostService(host, transport, seatIds, AiSeatDriver(aiLogics)).start()

        // La IA jugó sola la ronda entera (Mus → lances → resolución) por el bucle del host.
        val phase = host.authoritativeState.gamePhase
        assertTrue(
            "Una mesa de solo IA debe auto-resolver la ronda; quedó en $phase",
            phase == GamePhase.ROUND_OVER || phase == GamePhase.GAME_OVER
        )
    }

    /** Suscribe un observador y devuelve un getter de la última vista recibida. */
    private fun capture(transport: FakeMatchTransport, seatId: String): () -> GameState {
        var latest: GameState? = null
        transport.observeView(seatId) { latest = it }
        return { latest ?: error("Sin vista para $seatId") }
    }
}
