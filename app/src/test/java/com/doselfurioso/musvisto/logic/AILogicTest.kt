package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class TestAILogger : AILogger {
    override fun log(decision: DecisionLog) { /* No hace nada */ }
}

class AILogicTest {

    private lateinit var aiLogic: AILogic
    private lateinit var gameLogic: MusGameLogic
    private lateinit var testPlayer: Player
    private lateinit var opponentPlayer: Player

    @Before
    fun setUp() {
        gameLogic = MusGameLogic { Random.Default }
        aiLogic = AILogic(gameLogic, TestAILogger())
        testPlayer = Player(id = "ai1", name = "IA Test", team = "teamB", avatarResId = 0, isAi = true)
        opponentPlayer = Player(id = "p1", name = "Humano", team = "teamA", avatarResId = 0)
    }

    // --- TESTS DE DESCARTE (EXISTENTES, SIN CAMBIOS) ---
    @Test
    fun `decideDiscard with 31 de juego, discards the lowest card`() {
        val hand = listOf(Card(Suit.OROS, Rank.SOTA), Card(Suit.COPAS, Rank.SOTA), Card(Suit.ESPADAS, Rank.SOTA), Card(Suit.BASTOS, Rank.AS))
        val playerWithHand = testPlayer.copy(hand = hand)
        val decision = aiLogic.decideDiscard(playerWithHand, "test1")
        assertEquals(1, decision.cardsToDiscard.size)
        assertTrue(decision.cardsToDiscard.contains(Card(Suit.BASTOS, Rank.AS)))
    }

    // --- NUEVOS TESTS DE DECISIONES DE LA IA ---

    @Test
    fun `makeDecision - IA should pass with a weak hand`() {
        // NUEVO: Comprueba el comportamiento conservador.
        val hand = listOf(Card(Suit.OROS, Rank.CUATRO), Card(Suit.COPAS, Rank.CINCO), Card(Suit.ESPADAS, Rank.SEIS), Card(Suit.BASTOS, Rank.SIETE))
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand)), gamePhase = GamePhase.GRANDE)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Paso)
    }

    @Test
    fun `makeDecision - IA should make a standard bet with a good hand`() {
        // NUEVO: Comprueba el envite estándar.
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.CABALLO), Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)) // Buena a Grande y Chica
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand)), gamePhase = GamePhase.GRANDE)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Envido)
        assertEquals(2, (decision.action as GameAction.Envido).amount)
    }

    @Test
    fun `makeDecision - IA should make a standard bet even with a great hand due to conservative logic`() {
        // --- TEST CORREGIDO ---
        // AHORA: Comprobamos que, con tres Reyes, la IA hace el envite estándar de 2,
        // porque su personalidad ya no es tan agresiva.
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.CABALLO))
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand)), gamePhase = GamePhase.GRANDE)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Envido)
        assertEquals(2, (decision.action as GameAction.Envido).amount) // Esperamos un envite de 2
    }

    @Test
    fun `makeDecision - IA should accept a bet if it has a strong advantage`() {
        // NUEVO: Comprueba la lógica de "Quiero".
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY)) // Mano invencible a Grande
        val bet = BetInfo(2, opponentPlayer.id, testPlayer.id)
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand)), gamePhase = GamePhase.GRANDE, currentBet = bet)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Quiero || decision.action is GameAction.Envido || decision.action is GameAction.Órdago) // Acepta o sube
    }

    @Test
    fun `makeDecision - IA should reject a bet if it has a weak hand`() {
        // NUEVO: Comprueba la lógica de "No Quiero".
        val hand = listOf(Card(Suit.OROS, Rank.CUATRO), Card(Suit.COPAS, Rank.CINCO), Card(Suit.ESPADAS, Rank.AS), Card(Suit.BASTOS, Rank.DOS)) // Mala a Grande
        val bet = BetInfo(4, opponentPlayer.id, testPlayer.id)
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand)), gamePhase = GamePhase.GRANDE, currentBet = bet)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.NoQuiero)
    }

    @Test
    fun `makeDecision - IA should be more aggressive when losing (riskFactor)`() {
        // NUEVO: Comprueba el efecto del riskFactor.
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.SIETE), Card(Suit.ESPADAS, Rank.SEIS), Card(Suit.BASTOS, Rank.CINCO)) // Mano mediocre
        val score = mapOf("teamA" to 30, "teamB" to 5) // La IA va perdiendo por mucho
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand), opponentPlayer), gamePhase = GamePhase.GRANDE, score = score)
        // Con esta mano y marcador igualado, pasaría. Perdiendo, debería envidar.
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Envido)
    }

    @Test
    fun `makeDecision - IA should consider an Órdago ONLY when opponent is about to win`() {
        // --- TEST CORREGIDO ---
        // AHORA: Forzamos la condición exacta para el órdago (rival con más de 30 puntos).
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY))
        val score = mapOf("teamA" to 35, "teamB" to 10) // El rival está a punto de ganar
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand), opponentPlayer), gamePhase = GamePhase.GRANDE, score = score)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertTrue(decision.action is GameAction.Órdago)
    }

    @Test
    fun `makeDecision - IA should NOT consider an Órdago if opponent is not close to winning`() {
        // NUEVO: Añadimos el caso contrario para asegurar que no canta órdago a la ligera.
        val hand = listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.REY), Card(Suit.ESPADAS, Rank.REY), Card(Suit.BASTOS, Rank.REY))
        val score = mapOf("teamA" to 10, "teamB" to 10) // Marcador igualado
        val gameState = GameState(players = listOf(testPlayer.copy(hand = hand), opponentPlayer), gamePhase = GamePhase.GRANDE, score = score)
        val decision = aiLogic.makeDecision(gameState, testPlayer.copy(hand = hand))
        assertFalse(decision.action is GameAction.Órdago) // NO debe cantar órdago
        assertTrue(decision.action is GameAction.Envido) // Debería hacer un envite normal
    }
    @Test
    fun `makeDecision - IA should NOT make a premature Órdago with 31`() {
        // NUEVO TEST: Recrea el caso del órdago inesperado.
        // Escenario: La IA (p3) tiene 31 de Juego, pero es la primera ronda y el marcador está a cero.
        // Responde a un envite de 2 del rival. NO debería cantar órdago.
        val hand = listOf(
            Card(Suit.OROS, Rank.CABALLO), // 10
            Card(Suit.COPAS, Rank.SOTA),   // 10
            Card(Suit.ESPADAS, Rank.SIETE),  // 7
            Card(Suit.BASTOS, Rank.CUATRO)  // 4
        ) // Total: 31
        val aiPlayer = testPlayer.copy(id = "p3", hand = hand)
        val humanPlayer = opponentPlayer.copy(id = "p2")
        val score = mapOf("teamA" to 0, "teamB" to 0)

        // El rival (p2) ha envidado 2, y ahora le toca a la IA (p3)
        val bet = BetInfo(amount = 2, bettingPlayerId = "p2", respondingPlayerId = "p3", pointsIfRejected = 1)
        val gameState = GameState(
            players = listOf(aiPlayer, humanPlayer),
            gamePhase = GamePhase.JUEGO,
            score = score,
            currentBet = bet,
            currentTurnPlayerId = "p3"
        )

        val decision = aiLogic.makeDecision(gameState, aiPlayer)

        // Verificamos que la decisión NO es un órdago
        assertFalse("La IA no debería cantar órdago en la primera ronda solo por tener 31", decision.action is GameAction.Órdago)
        // La decisión correcta sería subir o, al menos, aceptar
        assertTrue(decision.action is GameAction.Envido || decision.action is GameAction.Quiero)
    }

}