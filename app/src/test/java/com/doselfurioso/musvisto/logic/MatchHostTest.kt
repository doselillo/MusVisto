package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameCommandCodec
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Prueba de integración del núcleo host-autoritativo (prep multijugador): los tres
 * primitivos de Fase 0 (GameCommand + reducer + StateRedactor) componen en [MatchHost].
 */
class MatchHostTest {

    private fun players() = listOf(
        Player(id = "p1", name = "A1", avatarResId = 0, team = "teamA"),
        Player(id = "p2", name = "B1", avatarResId = 0, isAi = true, team = "teamB"),
        Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
        Player(id = "p4", name = "B2", avatarResId = 0, isAi = true, team = "teamB")
    )

    /** Estado de una mano recién repartida en fase MUS, determinista (semilla fija). */
    private fun dealtMusState(): GameState {
        val logic = MusGameLogic(Random(0))
        val deck = logic.shuffleDeck(logic.createDeck())
        val (dealt, rest) = logic.dealCards(players(), deck, "p1")
        return GameState(
            players = dealt,
            deck = rest,
            gamePhase = GamePhase.MUS,
            currentTurnPlayerId = "p1",
            manoPlayerId = "p1",
            playersInLance = players().map { it.id }.toSet(),
            // El host computa availableActions por asiento al entrar en MUS; el
            // reducer las exige (processAction valida la acción contra ellas) y son
            // @Transient → no viajan por la red, las recalcula el host.
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    @Test
    fun `viewFor redacta la mano ajena pero el host conserva el estado completo`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())

        val p1View = host.viewFor("p1")
        assertEquals(4, p1View.players.first { it.id == "p1" }.hand.size)
        assertTrue(p1View.players.first { it.id == "p2" }.hand.isEmpty())
        assertTrue("La vista no expone el mazo", p1View.deck.isEmpty())

        // El host sigue teniendo TODAS las manos (es la autoridad).
        assertTrue(host.authoritativeState.players.all { it.hand.size == 4 })
    }

    @Test
    fun `submitCommand aplica el comando al estado autoritativo`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        val before = host.authoritativeState

        host.submitCommand("p1", GameCommand.Mus)

        assertNotEquals("Pedir Mus debe avanzar el estado", before, host.authoritativeState)
    }

    @Test
    fun `un comando que viaja por el codec produce el mismo estado que el directo`() {
        val initial = dealtMusState()
        val direct = MatchHost(MusGameLogic(Random(0)), initial)
        val overWire = MatchHost(MusGameLogic(Random(0)), initial)

        direct.submitCommand("p1", GameCommand.Mus)
        // Simula el viaje por red: encode -> JSON -> decode.
        val decoded = GameCommandCodec.decode(GameCommandCodec.encode(GameCommand.Mus))
        overWire.submitCommand("p1", decoded)

        // Igualdad LÓGICA: el reducer sella cada acción con LastActionInfo.seq =
        // nanoTime (único por construcción, @Transient → no viaja por la red), así
        // que se normaliza antes de comparar. El resto del estado es determinista.
        assertEquals(stable(direct.authoritativeState), stable(overWire.authoritativeState))
    }

    /** Quita los sellos de anuncio (seq = nanoTime, no deterministas ni de red). */
    private fun stable(s: GameState) = s.copy(
        lastAction = null, actionLog = emptyList(), currentLanceActions = emptyMap()
    )

    @Test
    fun `el descarte autonomo inyecta sus cartas y el host lo aplica`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        // Los 4 piden Mus en orden de turno → fase DISCARD (mano al descarte).
        repeat(4) { host.submitCommand(host.authoritativeState.currentTurnPlayerId!!, GameCommand.Mus) }
        assertEquals(GamePhase.DISCARD, host.authoritativeState.gamePhase)

        // El comando de descarte LLEVA sus cartas (autónomo): el host las inyecta en
        // selectedCardsForDiscard antes de reducir; sin eso el reducer lo ignora.
        val mano = host.authoritativeState.currentTurnPlayerId!!
        val toDiscard = host.authoritativeState.players.first { it.id == mano }.hand.take(2)
        host.submitCommand(mano, GameCommand.Discard(toDiscard))

        assertEquals(2, host.authoritativeState.discardCounts[mano])
    }

    @Test
    fun `tras aplicar un comando, la vista del rival sigue redactada`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        host.submitCommand("p1", GameCommand.Mus)

        val p2View = host.viewFor("p2")
        assertEquals(4, p2View.players.first { it.id == "p2" }.hand.size)
        listOf("p1", "p3", "p4").forEach {
            assertTrue("La mano de $it debe seguir oculta a p2", p2View.players.first { p -> p.id == it }.hand.isEmpty())
        }
    }
}
