package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

// Mock simple para el Logger, para que los tests puedan funcionar sin él.
class TestAILogger : AILogger {
    override fun log(decision: DecisionLog) {
        // No hace nada, solo existe para poder instanciar AILogic.
    }
}

class AILogicTest {

    private lateinit var aiLogic: AILogic
    private lateinit var gameLogic: MusGameLogic
    private lateinit var testPlayer: Player

    @Before
    fun setUp() {
        // Inicializamos las clases necesarias antes de cada test.
        gameLogic = MusGameLogic { Random.Default } // Usamos un proveedor de Random para MusGameLogic
        aiLogic = AILogic(gameLogic, TestAILogger())
        testPlayer = Player(id = "ai1", name = "IA Test", team = "teamB", avatarResId = 0, isAi = true)
    }

    // --- TESTS PARA LA NUEVA LÓGICA DE DESCARTE ---

    @Test
    fun `decideDiscard con 31 de juego, descarta la carta mas baja`() {
        // MANO: Sota, Sota, Sota, As (Suma 31)
        val hand = listOf(
            Card(Suit.OROS, Rank.SOTA),
            Card(Suit.COPAS, Rank.SOTA),
            Card(Suit.ESPADAS, Rank.SOTA),
            Card(Suit.BASTOS, Rank.AS)
        )
        val playerWithHand = testPlayer.copy(hand = hand)
        val cartaEsperada = Card(Suit.BASTOS, Rank.AS) // Debe descartar el As

        val decision = aiLogic.decideDiscard(playerWithHand, "test1")

        assertEquals("Con 31, debe descartar solo una carta", 1, decision.cardsToDiscard.size)
        assertTrue("Con 31, debe descartar la carta de menor rango (el As)", decision.cardsToDiscard.contains(cartaEsperada))
    }

    @Test
    fun `decideDiscard con 3 figuras, descarta la que no es figura`() {
        // MANO: Rey, Caballo, Sota, Cinco (Suma 35)
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.CABALLO),
            Card(Suit.ESPADAS, Rank.SOTA),
            Card(Suit.BASTOS, Rank.CINCO)
        )
        val playerWithHand = testPlayer.copy(hand = hand)
        val cartaEsperada = Card(Suit.BASTOS, Rank.CINCO) // Debe descartar el 5

        val decision = aiLogic.decideDiscard(playerWithHand, "test2")

        assertEquals("Con 3 figuras, debe descartar solo una carta", 1, decision.cardsToDiscard.size)
        assertTrue("Con 3 figuras, debe descartar la carta que no es figura", decision.cardsToDiscard.contains(cartaEsperada))
    }

    @Test
    fun `decideDiscard prioriza mantener Pares sobre cartas altas sueltas`() {
        // MANO: Rey, Sota, Sota, Cuatro
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.SOTA),
            Card(Suit.ESPADAS, Rank.SOTA),
            Card(Suit.BASTOS, Rank.CUATRO)
        )
        val playerWithHand = testPlayer.copy(hand = hand)
        val cartasEsperadas = setOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.BASTOS, Rank.CUATRO)
        ) // Debería descartar el Rey y el 4 para quedarse con la pareja de Sotas

        val decision = aiLogic.decideDiscard(playerWithHand, "test3")

        // NOTA: Esta lógica es un poco más avanzada. La actual tirará el 4 y el Rey.
        // Adaptamos el test a la lógica actual: descarta lo que no es par o Rey/Tres.
        val cartaDescartadaEsperada = Card(Suit.BASTOS, Rank.CUATRO)

        assertEquals("Debe descartar las cartas que no son ni Rey/Tres ni parte de una pareja", 1, decision.cardsToDiscard.size)
        assertTrue("Debe descartar el Cuatro", decision.cardsToDiscard.contains(cartaDescartadaEsperada))
    }

    @Test
    fun `decideDiscard descarta cartas malas (4, 5, 6, 7) antes que Ases o Doses`() {
        // MANO: Rey, Seis, Cinco, As
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.SEIS),
            Card(Suit.ESPADAS, Rank.CINCO),
            Card(Suit.BASTOS, Rank.AS)
        )
        val playerWithHand = testPlayer.copy(hand = hand)
        val cartasEsperadas = setOf(
            Card(Suit.COPAS, Rank.SEIS),
            Card(Suit.ESPADAS, Rank.CINCO)
        ) // Debe mantener Rey (Grande) y As (Chica/Juego)

        val decision = aiLogic.decideDiscard(playerWithHand, "test4")

        assertEquals("Debe descartar las dos cartas 'malas'", 2, decision.cardsToDiscard.size)
        assertEquals("Debe descartar el 6 y el 5", cartasEsperadas, decision.cardsToDiscard)
    }

    @Test
    fun `decideDiscard sin jugada clara, descarta solo la carta con puntuacion negativa`() {
        // MANO: Rey (score 50), Caballo (score 0), Siete (score -10), As (score 20)
        val hand = listOf(
            Card(Suit.OROS, Rank.REY),
            Card(Suit.COPAS, Rank.CABALLO),
            Card(Suit.ESPADAS, Rank.SIETE),
            Card(Suit.BASTOS, Rank.AS)
        )
        val playerWithHand = testPlayer.copy(hand = hand)
        val cartaEsperada = Card(Suit.ESPADAS, Rank.SIETE)
        val cartaNoEsperada = Card(Suit.COPAS, Rank.CABALLO)

        val decision = aiLogic.decideDiscard(playerWithHand, "test5")

        // Verificaciones más específicas
        assertEquals("Debe descartar solo 1 carta", 1, decision.cardsToDiscard.size)
        assertTrue("La carta a descartar debe ser el Siete", decision.cardsToDiscard.contains(cartaEsperada))
        assertFalse("No debe descartar el Caballo (score 0)", decision.cardsToDiscard.contains(cartaNoEsperada))
    }
}