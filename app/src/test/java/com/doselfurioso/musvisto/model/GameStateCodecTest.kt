package com.doselfurioso.musvisto.model

import com.doselfurioso.musvisto.logic.MusGameLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * El payload de la VISTA (GameState redactado) debe sobrevivir el viaje por JSON
 * que hará [com.doselfurioso.musvisto.logic.FirebaseMatchTransport] sobre RTDB.
 * De-riskea el transporte real ANTES de tocar la red.
 */
class GameStateCodecTest {

    private fun dealtState(): GameState {
        val logic = MusGameLogic(Random(0))
        val players = listOf(
            Player(id = "p1", name = "A1", avatarResId = 0, team = "teamA"),
            Player(id = "p2", name = "B1", avatarResId = 0, isAi = true, team = "teamB"),
            Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
            Player(id = "p4", name = "B2", avatarResId = 0, isAi = true, team = "teamB")
        )
        val deck = logic.shuffleDeck(logic.createDeck())
        val (dealt, rest) = logic.dealCards(players, deck, "p1")
        return GameState(
            players = dealt, deck = rest, gamePhase = GamePhase.GRANDE,
            currentTurnPlayerId = "p1", manoPlayerId = "p1",
            score = mapOf("teamA" to 12, "teamB" to 7),
            agreedBets = mapOf(GamePhase.GRANDE to 2),
            playersInLance = setOf("p1", "p2", "p3", "p4"),
            availableActions = listOf(GameAction.Paso) // @Transient: NO debe sobrevivir
        )
    }

    @Test
    fun `el GameState sobrevive el round-trip JSON`() {
        val original = dealtState()
        val restored = GameStateCodec.decode(GameStateCodec.encode(original))

        assertEquals(original.gamePhase, restored.gamePhase)
        assertEquals(original.currentTurnPlayerId, restored.currentTurnPlayerId)
        assertEquals(original.manoPlayerId, restored.manoPlayerId)
        assertEquals(original.score, restored.score)
        assertEquals(original.agreedBets, restored.agreedBets)
        assertEquals(original.playersInLance, restored.playersInLance)
        // Las manos viajan: cada jugador conserva sus 4 cartas exactas.
        assertEquals(
            original.players.map { it.id to it.hand },
            restored.players.map { it.id to it.hand }
        )
    }

    @Test
    fun `los campos @Transient no viajan (los recalcula el host)`() {
        val restored = GameStateCodec.decode(GameStateCodec.encode(dealtState()))
        assertTrue(restored.availableActions.isEmpty())
    }
}
