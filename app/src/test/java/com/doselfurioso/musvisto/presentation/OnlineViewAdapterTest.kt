package com.doselfurioso.musvisto.presentation

import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LastActionView
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.ScoreBreakdown
import com.doselfurioso.musvisto.model.ScoreDetail
import com.doselfurioso.musvisto.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptador vista-del-host → GameState para GameTable (online, Slice B). */
class OnlineViewAdapterTest {

    private fun card(s: Suit, r: Rank) = Card(s, r)
    private fun fullHand() = listOf(
        card(Suit.OROS, Rank.REY), card(Suit.COPAS, Rank.AS),
        card(Suit.ESPADAS, Rank.CINCO), card(Suit.BASTOS, Rank.SIETE)
    )

    /** Vista redactada como la que publica el host: solo [mySeat] tiene cartas (o todas si reveal). */
    private fun redactedView(mySeat: String, reveal: Boolean = false): GameState {
        val players = listOf("p1", "p2", "p3", "p4").mapIndexed { i, id ->
            Player(
                id = id, name = id, avatarResId = 0,
                team = if (i % 2 == 0) "teamA" else "teamB",
                hand = if (id == mySeat || reveal) fullHand() else emptyList()
            )
        }
        return GameState(
            players = players, gamePhase = GamePhase.GRANDE,
            currentTurnPlayerId = mySeat, revealAllHands = reveal
        )
    }

    @Test
    fun `rellena manos ajenas a 4 reversos y conserva la propia`() {
        val out = adaptOnlineView(
            redactedView("p2"), mySeatId = "p2",
            selectedCards = emptySet(), isSelectingBet = false
        )
        assertEquals(4, out.players.first { it.id == "p2" }.hand.size)
        out.players.filter { it.id != "p2" }.forEach {
            assertEquals("rival ${it.id} debe tener 4 backs", 4, it.hand.size)
        }
    }

    @Test
    fun `en el ensene (revealAllHands) no rellena, llegan las manos reales`() {
        val out = adaptOnlineView(
            redactedView("p2", reveal = true), mySeatId = "p2",
            selectedCards = emptySet(), isSelectingBet = false
        )
        out.players.forEach { assertEquals(4, it.hand.size) }
        assertTrue(out.revealAllHands)
    }

    @Test
    fun `mapea availableCommands a availableActions e inyecta el estado de UI local`() {
        val view = redactedView("p1").copy(
            availableCommands = listOf(GameCommand.Pass, GameCommand.Ordago, GameCommand.Bet(2))
        )
        val selected = setOf(card(Suit.OROS, Rank.REY))
        val out = adaptOnlineView(view, "p1", selectedCards = selected, isSelectingBet = true)
        assertTrue(out.availableActions.any { it is GameAction.Paso })
        assertTrue(out.availableActions.any { it is GameAction.Órdago })
        assertTrue(out.availableActions.any { it is GameAction.Envido })
        assertEquals(selected, out.selectedCardsForDiscard)
        assertTrue(out.isSelectingBet)
    }

    @Test
    fun `lastActionView se mapea a anuncio sobre el asiento que actuo`() {
        val view = redactedView("p1").copy(lastActionView = LastActionView("p3", GameCommand.Pass))
        val out = adaptOnlineView(view, "p1", emptySet(), false)
        assertEquals(1, out.currentLanceActions.size)
        assertTrue(out.currentLanceActions["p3"]?.action is GameAction.Paso)
    }

    @Test
    fun `si mi asiento es teamB, intercambia los campos de equipo (mi equipo = NOSOTROS)`() {
        // p2 = teamB en redactedView (índices pares = teamA, impares = teamB).
        val view = redactedView("p2").copy(
            score = mapOf("teamA" to 10, "teamB" to 25),
            chicosWon = mapOf("teamA" to 1, "teamB" to 0),
            winningTeam = "teamB",
            scoreBreakdown = ScoreBreakdown(
                teamAScoreDetails = listOf(ScoreDetail("A", 3)),
                teamBScoreDetails = listOf(ScoreDetail("B", 7))
            )
        )
        val out = adaptOnlineView(view, "p2", emptySet(), false)
        // teamB es MI equipo → tras el swap su marcador/ganador salen como teamA (NOSOTROS).
        assertEquals(25, out.score["teamA"])
        assertEquals(10, out.score["teamB"])
        assertEquals(1, out.chicosWon["teamB"])
        assertEquals("teamA", out.winningTeam)
        assertEquals(7, out.scoreBreakdown!!.teamAScoreDetails.first().points)
    }

    @Test
    fun `si mi asiento es teamA, NO intercambia (ya soy NOSOTROS)`() {
        val view = redactedView("p1").copy(
            score = mapOf("teamA" to 10, "teamB" to 25), winningTeam = "teamB"
        )
        val out = adaptOnlineView(view, "p1", emptySet(), false)
        assertEquals(10, out.score["teamA"])
        assertEquals("teamB", out.winningTeam)
    }
}
