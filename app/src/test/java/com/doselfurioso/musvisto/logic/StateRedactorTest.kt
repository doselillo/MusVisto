package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fog of war del multijugador (Fase 0.3): [StateRedactor.redactFor]. */
class StateRedactorTest {

    private fun hand(vararg ranks: Rank) = ranks.map { Card(Suit.OROS, it) }

    private fun state(revealAll: Boolean = false) = GameState(
        players = listOf(
            Player(id = "p1", name = "Yo", hand = hand(Rank.REY, Rank.REY), avatarResId = 0, team = "teamA"),
            Player(id = "p2", name = "Rival", hand = hand(Rank.AS, Rank.AS), avatarResId = 0, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Pareja", hand = hand(Rank.SOTA, Rank.SOTA), avatarResId = 0, isAi = true, team = "teamA"),
            Player(id = "p4", name = "Rival2", hand = hand(Rank.SIETE, Rank.SIETE), avatarResId = 0, isAi = true, team = "teamB")
        ),
        deck = hand(Rank.CABALLO, Rank.CINCO),
        currentTurnPlayerId = "p1",
        score = mapOf("teamA" to 12, "teamB" to 7),
        revealAllHands = revealAll
    )

    @Test
    fun `redactFor conserva mi mano y oculta las ajenas`() {
        val redacted = StateRedactor.redactFor("p1", state())

        assertEquals(hand(Rank.REY, Rank.REY), redacted.players.first { it.id == "p1" }.hand)
        listOf("p2", "p3", "p4").forEach { id ->
            assertTrue("La mano de $id debe ocultarse", redacted.players.first { it.id == id }.hand.isEmpty())
        }
    }

    @Test
    fun `redactFor oculta el compañero igual que a los rivales`() {
        // En el Mus no ves la mano de tu pareja: se señaliza.
        val redacted = StateRedactor.redactFor("p1", state())
        assertTrue(redacted.players.first { it.id == "p3" }.hand.isEmpty())
    }

    @Test
    fun `redactFor siempre oculta el mazo`() {
        assertTrue(StateRedactor.redactFor("p1", state()).deck.isEmpty())
        assertTrue(StateRedactor.redactFor("p1", state(revealAll = true)).deck.isEmpty())
    }

    @Test
    fun `redactFor conserva la informacion publica`() {
        val redacted = StateRedactor.redactFor("p1", state())
        assertEquals("p1", redacted.currentTurnPlayerId)
        assertEquals(mapOf("teamA" to 12, "teamB" to 7), redacted.score)
    }

    @Test
    fun `redactFor con revealAllHands deja ver todas las manos`() {
        val redacted = StateRedactor.redactFor("p2", state(revealAll = true))
        redacted.players.forEach { assertTrue("Enseñe: ${it.id} visible", it.hand.isNotEmpty()) }
    }

    @Test
    fun `redactFor desde otro asiento conserva ESA mano`() {
        val redacted = StateRedactor.redactFor("p2", state())
        assertEquals(hand(Rank.AS, Rank.AS), redacted.players.first { it.id == "p2" }.hand)
        assertTrue(redacted.players.first { it.id == "p1" }.hand.isEmpty())
    }
}
