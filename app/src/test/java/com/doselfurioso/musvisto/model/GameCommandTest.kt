package com.doselfurioso.musvisto.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase 0.1 del plan multijugador (docs/context/MULTIPLAYER_PLAN.md). Fija el
 * contrato del comando de red: round-trip de serialización, mapeo ↔ GameAction
 * sin pérdida y, sobre todo, que el formato NO arrastra recursos de UI Android.
 */
class GameCommandTest {

    private val allCommands = listOf(
        GameCommand.Mus,
        GameCommand.NoMus,
        GameCommand.Discard(listOf(Card(Suit.OROS, Rank.REY), Card(Suit.COPAS, Rank.AS))),
        GameCommand.Continue,
        GameCommand.NewGame,
        GameCommand.Tengo,
        GameCommand.NoTengo,
        GameCommand.Pass,
        GameCommand.Bet(5),
        GameCommand.Accept,
        GameCommand.Decline,
        GameCommand.Ordago,
    )

    @Test
    fun `cada comando sobrevive un round-trip de serializacion`() {
        for (cmd in allCommands) {
            val restored = GameCommandCodec.decode(GameCommandCodec.encode(cmd))
            assertEquals(cmd, restored)
        }
    }

    @Test
    fun `el comando de descarte conserva las cartas`() {
        val cards = listOf(Card(Suit.ESPADAS, Rank.TRES), Card(Suit.BASTOS, Rank.CINCO))
        val restored = GameCommandCodec.decode(GameCommandCodec.encode(GameCommand.Discard(cards)))
        assertEquals(GameCommand.Discard(cards), restored)
    }

    @Test
    fun `accion de UI mapea a comando y vuelve a accion equivalente`() {
        assertEquals(GameAction.Mus, GameAction.Mus.toCommand()?.toAction())
        assertEquals(GameAction.Paso, GameAction.Paso.toCommand()?.toAction())
        assertEquals(GameAction.Quiero, GameAction.Quiero.toCommand()?.toAction())
        assertEquals(GameAction.Órdago, GameAction.Órdago.toCommand()?.toAction())
    }

    @Test
    fun `el envido conserva la cantidad a traves del comando`() {
        val cmd = GameAction.Envido(4).toCommand()
        assertEquals(GameCommand.Bet(4), cmd)
        assertEquals(GameAction.Envido(4), cmd?.toAction())
    }

    @Test
    fun `el descarte toma las cartas seleccionadas del estado`() {
        val cards = setOf(Card(Suit.OROS, Rank.SOTA))
        assertEquals(GameCommand.Discard(cards.toList()), GameAction.ConfirmDiscard.toCommand(cards))
    }

    @Test
    fun `las acciones puramente de UI no producen comando de red`() {
        assertNull(GameAction.ToggleBetSelector.toCommand())
        assertNull(GameAction.CancelBetSelection.toCommand())
        assertNull(GameAction.TogglePauseMenu.toCommand())
        assertNull(GameAction.ShowGesture.toCommand())
        assertNull(GameAction.LogAction("debug").toCommand())
    }

    @Test
    fun `el JSON del comando no arrastra recursos ni texto de UI Android`() {
        // GameAction lleva R.drawable / displayText; el comando NO debe llevarlos.
        val jsonBet = GameCommandCodec.encode(GameCommand.Bet(2))
        assertTrue("el importe del envite debe viajar", jsonBet.contains("2"))
        assertFalse("no debe arrastrar iconos", jsonBet.contains("drawable"))
        assertFalse("no debe arrastrar texto de UI", jsonBet.contains("displayText"))
        assertFalse("no debe arrastrar iconResId", jsonBet.contains("iconResId"))
    }
}
