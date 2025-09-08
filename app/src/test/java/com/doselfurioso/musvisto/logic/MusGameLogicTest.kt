package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Provider
import kotlin.random.Random

class MusGameLogicTest {

    private lateinit var gameLogic: MusGameLogic

    // Datos comunes para los tests
    private lateinit var players: List<Player>
    private lateinit var deck: List<Card>

    @Before
    fun setUp() {
        // --- CORRECCIÓN CLAVE ---
        // El constructor de MusGameLogic ahora necesita un `Provider<Random>`.
        // Para los tests, creamos un proveedor simple que devuelve una instancia estándar de Random.
        // Esto soluciona el error `TODO()` que hacía que todos los tests fallaran.
        val randomProvider = Provider<Random> { Random.Default }
        gameLogic = MusGameLogic(randomProvider)

        players = listOf(
            Player(id = "p1", name = "Ana", team = "teamA", avatarResId = 0),
            Player(id = "p2", name = "Luis", team = "teamB", avatarResId = 0),
            Player(id = "p3", name = "Sara", team = "teamA", avatarResId = 0),
            Player(id = "p4", name = "Juan", team = "teamB", avatarResId = 0)
        )
        deck = gameLogic.createDeck()
    }

    @Test
    fun `createDeck should return a deck with 40 cards`() {
        assertEquals(40, deck.size)
    }

    @Test
    fun `dealCards should give 4 cards to each player and leave 24 in deck`() {
        val (updatedPlayers, remainingDeck) = gameLogic.dealCards(players, deck, "p1")

        assertEquals(4, updatedPlayers[0].hand.size)
        assertEquals(4, updatedPlayers[1].hand.size)
        assertEquals(4, updatedPlayers[2].hand.size)
        assertEquals(4, updatedPlayers[3].hand.size)
        assertEquals(24, remainingDeck.size)
    }

    // --- TESTS DE PARES (CORREGIDOS Y AMPLIADOS) ---

    @Test
    fun `getHandPares should detect Pares with Rey and Tres`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.TRES), Card(Suit.ESPADAS, Rank.SIETE), Card(Suit.BASTOS, Rank.CINCO))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Debería detectar Pares con Rey y Tres, que cuentan como Reyes", play is ParesPlay.Pares)
        assertEquals(Rank.REY, (play as ParesPlay.Pares).rank)
    }

    @Test
    fun `getHandPares should detect Medias with two Reyes and one Tres`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.TRES), Card(Suit.BASTOS, Rank.CINCO))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Debería detectar Medias con dos Reyes y un Tres", play is ParesPlay.Medias)
        assertEquals(Rank.REY, (play as ParesPlay.Medias).rank)
    }

    @Test
    fun `getHandPares should detect Duples with Reyes-Treses and Ases-Doses`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.TRES), Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Debería detectar Duples con las equivalencias de Reyes/Treses y Ases/Doses", play is ParesPlay.Duples)
        assertEquals(Rank.REY, (play as ParesPlay.Duples).highPair)
        assertEquals(Rank.AS, play.lowPair)
    }

    @Test
    fun `getHandPares should detect Duples with four identical cards (e g , four Kings)`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Cuatro Reyes deberían contarse como Duples", play is ParesPlay.Duples)
        assertEquals("El par alto de cuatro Reyes es Rey", Rank.REY, (play as ParesPlay.Duples).highPair)
        assertEquals("El par bajo de cuatro Reyes también es Rey", Rank.REY, play.lowPair)
    }

    // --- TESTS DE GANADORES (CORREGIDOS Y AMPLIADOS) ---

    @Test
    fun `getGrandeWinner should return player with the highest unique card`() {
        val testPlayers = listOf(
            players[0].copy(hand = listOf(Card(Suit.OROS, Rank.SIETE))),
            players[1].copy(hand = listOf(Card(Suit.OROS, Rank.REY))), // Ganador
            players[2].copy(hand = listOf(Card(Suit.OROS, Rank.CABALLO))),
            players[3].copy(hand = listOf(Card(Suit.OROS, Rank.AS)))
        )
        val gameState = GameState(players = testPlayers, manoPlayerId = "p1")
        val winner = gameLogic.getGrandeWinner(gameState)
        assertEquals(testPlayers[1], winner)
    }

    @Test
    fun `getGrandeWinner should handle ties by player order (mano)`() {
        val p1 = players[0].copy(hand = listOf(Card(Suit.OROS, Rank.REY))) // Mano y ganador por desempate
        val p2 = players[1].copy(hand = listOf(Card(Suit.COPAS, Rank.REY)))
        val gameState = GameState(players = listOf(p1, p2), manoPlayerId = "p1")
        val winner = gameLogic.getGrandeWinner(gameState)
        assertEquals(p1.id, winner?.id)
    }

    @Test
    fun `getChicaWinner should return player with the lowest unique card`() {
        val testPlayers = listOf(
            players[0].copy(hand = listOf(Card(Suit.OROS, Rank.SIETE))),
            players[1].copy(hand = listOf(Card(Suit.OROS, Rank.REY))),
            players[2].copy(hand = listOf(Card(Suit.OROS, Rank.CABALLO))),
            players[3].copy(hand = listOf(Card(Suit.OROS, Rank.AS))) // Ganador
        )
        val gameState = GameState(players = testPlayers, manoPlayerId = "p1")
        val winner = gameLogic.getChicaWinner(gameState)
        assertEquals(testPlayers[3], winner)
    }

    @Test
    fun `getJuegoWinner should prioritize 31 over 32, regardless of mano`() {
        val playerWith32 = players[1].copy(hand = listOf(Card(Suit.OROS, Rank.SOTA), Card(Suit.ESPADAS, Rank.SOTA), Card(Suit.COPAS, Rank.SIETE), Card(Suit.BASTOS, Rank.CINCO))) // 32
        val playerWith31 = players[0].copy(hand = listOf(Card(Suit.OROS, Rank.SOTA), Card(Suit.ESPADAS, Rank.SOTA), Card(Suit.COPAS, Rank.SOTA), Card(Suit.BASTOS, Rank.AS))) // 31

        // Poniendo al jugador con 32 como "mano" para forzar que la victoria sea por la jugada y no por la posición.
        val gameState = GameState(players = listOf(playerWith32, playerWith31), manoPlayerId = "p2")
        val winner = gameLogic.getJuegoWinner(gameState)
        assertEquals("31 siempre debe ganar a 32, sin importar quién sea mano", playerWith31, winner)
    }

    @Test
    fun `score of a rejected bet is calculated correctly`() {
        // Escenario: p1(A) envida 2. p2(B) sube a 5. p3(A), el siguiente en turno, rechaza.
        // El equipo B debe ganar 2 puntos.

        // 1. p1 (teamA) envida 2. El turno pasa a p2.
        val initialState = GameState(
            players = players,
            currentTurnPlayerId = "p1",
            gamePhase = GamePhase.GRANDE,
            manoPlayerId = "p1",
            // --- LA CORRECCIÓN CLAVE ESTÁ AQUÍ ---
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
        val stateAfterEnvido = gameLogic.processAction(
            initialState,
            GameAction.Envido(2), "p1"
        )
        assertEquals("p2", stateAfterEnvido.currentTurnPlayerId)

        // 2. p2 (teamB) sube 3 más. El turno pasa al siguiente oponente, p3.
        val stateAfterSubida = gameLogic.processAction(
            stateAfterEnvido,
            GameAction.Envido(3), "p2"
        )
        assertEquals("p3", stateAfterSubida.currentTurnPlayerId) // Verificamos que el turno es de p3
        assertEquals(2, stateAfterSubida.currentBet?.pointsIfRejected)

        // 3. p3 (teamA), el compañero de p1, rechaza. Ahora sí es el final del envite.
        val finalState = gameLogic.processAction(
            stateAfterSubida,
            GameAction.NoQuiero, "p3"
        )

        // Verificamos que el equipo B ha ganado 2 puntos
        assertEquals(2, finalState.score["teamB"])
        assertEquals(0, finalState.score["teamA"])
    }
}