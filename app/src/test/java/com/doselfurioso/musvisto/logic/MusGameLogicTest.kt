package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.ParesPlay
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit
import com.doselfurioso.musvisto.logic.MusGameLogic.*
import org.junit.Assert.*
import org.junit.Test

class MusGameLogicTest {

    @Test
    fun `createDeck should return a deck with 40 cards`() {
        // Arrange: No setup needed for this simple test

        // Act: Call the function we want to test
        val deck = MusGameLogic.createDeck()

        // Assert: Check if the result is what we expect
        assertEquals(40, deck.size)
    }

    @Test
    fun `dealCards should give 4 cards to each of the 4 players`() {
        // Arrange: Create the necessary objects for the test
        val deck = MusGameLogic.createDeck()
        val players = listOf(
            com.doselfurioso.musvisto.model.Player(id = "p1", name = "Ana"),
            com.doselfurioso.musvisto.model.Player(id = "p2", name = "Luis"),
            com.doselfurioso.musvisto.model.Player(id = "p3", name = "Sara"),
            com.doselfurioso.musvisto.model.Player(id = "p4", name = "Juan")
        )

        // Act: Call the function
        val (updatedPlayers, _) = MusGameLogic.dealCards(players, deck)

        // Assert: Check the results
        assertEquals(4, updatedPlayers[0].hand.size)
        assertEquals(4, updatedPlayers[1].hand.size)
        assertEquals(4, updatedPlayers[2].hand.size)
        assertEquals(4, updatedPlayers[3].hand.size)
    }

    @Test
    fun `dealCards should leave 24 cards in the deck`() {
        // Arrange
        val deck = MusGameLogic.createDeck()
        val players = listOf(
            com.doselfurioso.musvisto.model.Player(id = "p1", name = "Ana"),
            com.doselfurioso.musvisto.model.Player(id = "p2", name = "Luis"),
            com.doselfurioso.musvisto.model.Player(id = "p3", name = "Sara"),
            com.doselfurioso.musvisto.model.Player(id = "p4", name = "Juan")
        )

        // Act
        val (_, remainingDeck) = MusGameLogic.dealCards(players, deck)

        // Assert
        assertEquals(24, remainingDeck.size)
    }

    @Test
    fun `getHighestCard should return King when present`() {
        // Arrange: Create a hand with various cards, including a King.
        val hand = listOf(
            Card(Suit.OROS, Rank.AS),
            Card(Suit.COPAS, Rank.SIETE),
            Card(Suit.ESPADAS, Rank.REY), // The best card for Grande
            Card(Suit.BASTOS, Rank.SOTA)
        )

        // Act: Call the function we are testing.
        val highestCard = MusGameLogic.getHighestCard(hand)

        // Assert: Check that the result is indeed the King.
        assertEquals(Rank.REY, highestCard?.rank)
    }

    @Test
    fun `getLowestCard should return Ace when present`() {
        // Arrange: Create a hand.
        val hand = listOf(
            Card(Suit.OROS, Rank.CINCO),
            Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.AS), // The best card for Chica
            Card(Suit.BASTOS, Rank.DOS)
        )

        // Act
        val lowestCard = MusGameLogic.getLowestCard(hand)

        // Assert
        assertEquals(Rank.AS, lowestCard?.rank)
    }
    @Test
    fun `getGrandeWinner should return player with the highest unique card`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf(Card(Suit.OROS, Rank.SIETE)))
        val player2 = Player("p2", "Luis", hand = listOf(Card(Suit.OROS, Rank.REY))) // Winner
        val player3 = Player("p3", "Sara", hand = listOf(Card(Suit.OROS, Rank.CABALLO)))
        val player4 = Player("p4", "Juan", hand = listOf(Card(Suit.OROS, Rank.AS)))
        val players = listOf(player1, player2, player3, player4)

        // Act
        val winner = MusGameLogic.getGrandeWinner(players)

        // Assert
        assertEquals(player2, winner)
    }

    @Test
    fun `getGrandeWinner should return the first player in case of a tie (mano wins)`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf(Card(Suit.OROS, Rank.REY))) // "Mano" with a King
        val player2 = Player("p2", "Luis", hand = listOf(Card(Suit.OROS, Rank.SIETE)))
        val player3 = Player("p3", "Sara", hand = listOf(Card(Suit.COPAS, Rank.REY))) // Other player with a King
        val player4 = Player("p4", "Juan", hand = listOf(Card(Suit.OROS, Rank.AS)))
        val players = listOf(player1, player2, player3, player4) // player1 is "mano"

        // Act
        val winner = MusGameLogic.getGrandeWinner(players)

        // Assert
        // The winner must be player1, because they are "mano" and tied for the best card.
        assertEquals(player1, winner)
    }

    @Test
    fun `getChicaWinner should return player with the lowest unique card`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf(Card(Suit.OROS, Rank.SIETE)))
        val player2 = Player("p2", "Luis", hand = listOf(Card(Suit.OROS, Rank.REY)))
        val player3 = Player("p3", "Sara", hand = listOf(Card(Suit.OROS, Rank.CABALLO)))
        val player4 = Player("p4", "Juan", hand = listOf(Card(Suit.OROS, Rank.AS))) // Winner
        val players = listOf(player1, player2, player3, player4)

        // Act
        val winner = MusGameLogic.getChicaWinner(players)

        // Assert
        assertEquals(player4, winner)
    }

    @Test
    fun `getChicaWinner should return the first player in case of a tie (mano wins)`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf(Card(Suit.OROS, Rank.AS))) // "Mano" with an Ace
        val player2 = Player("p2", "Luis", hand = listOf(Card(Suit.OROS, Rank.SIETE)))
        val player3 = Player("p3", "Sara", hand = listOf(Card(Suit.COPAS, Rank.AS))) // Other player with an Ace
        val player4 = Player("p4", "Juan", hand = listOf(Card(Suit.OROS, Rank.REY)))
        val players = listOf(player1, player2, player3, player4) // player1 is "mano"

        // Act
        val winner = MusGameLogic.getChicaWinner(players)

        // Assert
        // The winner must be player1, because they are "mano" and tied for the best card.
        assertEquals(player1, winner)
    }

    @Test
    fun `getHandPares should detect Duples correctly`() {
        // Arrange
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.AS),
            Card(Suit.BASTOS, Rank.AS)
        )
        // Act
        val play = MusGameLogic.getHandPares(hand)
        // Assert
        assertTrue(play is ParesPlay.Duples)
        assertEquals(Rank.REY, (play as ParesPlay.Duples).highPair)
        assertEquals(Rank.AS, play.lowPair)
    }

    @Test
    fun `getHandPares should detect Medias correctly`() {
        // Arrange
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY),
            Card(Suit.BASTOS, Rank.AS)
        )
        // Act
        val play = MusGameLogic.getHandPares(hand)
        // Assert
        assertTrue(play is ParesPlay.Medias)
        assertEquals(Rank.REY, (play as ParesPlay.Medias).rank)
    }

    @Test
    fun `getHandPares should detect Pares correctly`() {
        // Arrange
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.SIETE),
            Card(Suit.BASTOS, Rank.AS)
        )
        // Act
        val play = MusGameLogic.getHandPares(hand)
        // Assert
        assertTrue(play is ParesPlay.Pares)
        assertEquals(Rank.REY, (play as ParesPlay.Pares).rank)
    }

    @Test
    fun `getHandPares should detect NoPares correctly`() {
        // Arrange
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.DOS),
            Card(Suit.ESPADAS, Rank.SIETE),
            Card(Suit.BASTOS, Rank.AS)
        )
        // Act
        val play = MusGameLogic.getHandPares(hand)
        // Assert
        assertTrue(play is ParesPlay.NoPares)
    }

    @Test
    fun `getParesWinner should favor Medias over Pares`() {
        // Arrange
        val playerWithPares = Player("p1", "Ana", hand = listOf(
            Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)
        ))
        val playerWithMedias = Player("p2", "Luis", hand = listOf( // Winner
            Card(Suit.OROS, Rank.AS), Card(Suit.COPAS, Rank.AS),
            Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)
        ))
        val players = listOf(playerWithPares, playerWithMedias)

        // Act
        val winner = MusGameLogic.getParesWinner(players)

        // Assert
        assertEquals(playerWithMedias, winner)
    }

    @Test
    fun `getParesWinner should favor higher rank on Pares tie`() {
        // Arrange
        val playerWithParesOfAces = Player("p1", "Ana", hand = listOf(
            Card(Suit.OROS, Rank.AS), Card(Suit.COPAS, Rank.AS),
            Card(Suit.ESPADAS, Rank.TRES), Card(Suit.BASTOS, Rank.DOS)
        ))
        val playerWithParesOfKings = Player("p2", "Luis", hand = listOf( // Winner
            Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.TRES), Card(Suit.BASTOS, Rank.DOS)
        ))
        val players = listOf(playerWithParesOfAces, playerWithParesOfKings)

        // Act
        val winner = MusGameLogic.getParesWinner(players)

        // Assert
        assertEquals(playerWithParesOfKings, winner)
    }

    @Test
    fun `getParesWinner should use mano rule on identical Pares`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf( // Winner (mano)
            Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)
        ))
        val player2 = Player("p2", "Luis", hand = listOf(
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY),
            Card(Suit.OROS, Rank.TRES), Card(Suit.COPAS, Rank.CUATRO)
        ))
        val players = listOf(player1, player2)

        // Act
        val winner = MusGameLogic.getParesWinner(players)

        // Assert
        assertEquals(player1, winner)
    }

    @Test
    fun `getParesWinner should return null if no one has pares`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf(
            Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.SIETE),
            Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)
        ))
        val player2 = Player("p2", "Luis", hand = listOf(
            Card(Suit.ESPADAS, Rank.SOTA), Card(Suit.BASTOS, Rank.CABALLO),
            Card(Suit.OROS, Rank.TRES), Card(Suit.COPAS, Rank.CUATRO)
        ))
        val players = listOf(player1, player2)

        // Act
        val winner = MusGameLogic.getParesWinner(players)

        // Assert
        assertNull(winner)
    }
    @Test
    fun `getJuegoWinner should correctly identify player with Juego`() {
        // Arrange
        val playerWithJuego = Player("p1", "Ana", hand = listOf( // 31 points
            Card(Suit.OROS, Rank.AS), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)
        ))
        val playerWithPunto = Player("p2", "Luis", hand = listOf( // 28 points
            Card(Suit.OROS, Rank.SIETE), Card(Suit.COPAS, Rank.SIETE),
            Card(Suit.ESPADAS, Rank.SIETE), Card(Suit.BASTOS, Rank.SIETE)
        ))
        val players = listOf(playerWithPunto, playerWithJuego)

        // Act
        val winner = MusGameLogic.getJuegoWinner(players)

        // Assert
        assertEquals(playerWithJuego, winner)
    }

    @Test
    fun `getJuegoWinner should favor 31 over 32`() {
        // Arrange
        val playerWith31 = Player("p1", "Ana", hand = listOf( // 31 points - Winner
            Card(Suit.OROS, Rank.AS), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)
        ))
        val playerWith32 = Player("p2", "Luis", hand = listOf( // 32 points
            Card(Suit.OROS, Rank.DOS), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)
        ))
        val players = listOf(playerWith32, playerWith31)

        // Act
        val winner = MusGameLogic.getJuegoWinner(players)

        // Assert
        assertEquals(playerWith31, winner)
    }

    @Test
    fun `getJuegoWinner should favor highest score at Punto when no one has Juego`() {
        // Arrange
        val playerWith27 = Player("p1", "Ana", hand = listOf(
            Card(Suit.OROS, Rank.SIETE), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.AS)
        ))
        val playerWith29 = Player("p2", "Luis", hand = listOf( // 29 points - Winner
            Card(Suit.OROS, Rank.SIETE), Card(Suit.COPAS, Rank.SIETE),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.CINCO)
        ))
        val players = listOf(playerWith27, playerWith29)

        // Act
        val winner = MusGameLogic.getJuegoWinner(players)

        // Assert
        assertEquals(playerWith29, winner)
    }

    @Test
    fun `getJuegoWinner should use mano rule on identical Juego score`() {
        // Arrange
        val player1 = Player("p1", "Ana", hand = listOf( // 34 points - Winner (mano)
            Card(Suit.OROS, Rank.CUATRO), Card(Suit.COPAS, Rank.REY),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)
        ))
        val player2 = Player("p2", "Luis", hand = listOf( // 34 points
            Card(Suit.OROS, Rank.SIETE), Card(Suit.COPAS, Rank.SIETE),
            Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)
        ))
        val players = listOf(player1, player2)

        // Act
        val winner = MusGameLogic.getJuegoWinner(players)

        // Assert
        assertEquals(player1, winner)
    }


}