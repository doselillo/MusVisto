package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MusGameLogicTest {

    // Creamos una instancia de la lógica para usar en todos los tests
    private lateinit var gameLogic: MusGameLogic

    // Datos comunes para los tests
    private lateinit var players: List<Player>
    private lateinit var deck: List<Card>

    @Before
    fun setUp() {
        // Esto se ejecuta antes de cada test
        gameLogic = MusGameLogic(
            random = TODO()
        )
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
        // Act: Ahora pasamos el manoId, que es un requisito
        val (updatedPlayers, remainingDeck) = gameLogic.dealCards(players, deck, "p1")

        // Assert
        assertEquals(4, updatedPlayers[0].hand.size)
        assertEquals(4, updatedPlayers[1].hand.size)
        assertEquals(4, updatedPlayers[2].hand.size)
        assertEquals(4, updatedPlayers[3].hand.size)
        assertEquals(24, remainingDeck.size)
    }

    // --- NUEVOS TESTS PARA LA LÓGICA DE PARES CORREGIDA ---

    @Test
    fun `getHandPares should detect Pares with Rey and Tres`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.TRES), Card(Suit.ESPADAS, Rank.SIETE), Card(Suit.BASTOS, Rank.CINCO))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Debería detectar Pares con Rey y Tres", play is ParesPlay.Pares)
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
        assertTrue("Debería detectar Duples con equivalencias", play is ParesPlay.Duples)
        assertEquals(Rank.REY, (play as ParesPlay.Duples).highPair)
        assertEquals(Rank.AS, play.lowPair)
    }

    @Test
    fun `getHandPares should detect Duples with four identical cards (e g , four Kings)`() {
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY))
        val play = gameLogic.getHandPares(hand)
        assertTrue("Cuatro Reyes deberían ser Duples", play is ParesPlay.Duples)
        assertEquals(Rank.REY, (play as ParesPlay.Duples).highPair)
    }

    // --- TESTS DE GANADORES ACTUALIZADOS ---

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
    fun `getJuegoWinner should favor 31 over 32`() {
        val playerWith31 = players[0].copy(hand = listOf(Card(Suit.OROS, Rank.AS), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)))
        val playerWith32 = players[1].copy(hand = listOf(Card(Suit.OROS, Rank.CINCO), Card(Suit.COPAS, Rank.SIETE), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)))
        val gameState = GameState(players = listOf(playerWith32, playerWith31), manoPlayerId = "p2")
        val winner = gameLogic.getJuegoWinner(gameState)
        assertEquals(playerWith31, winner)
    }
}