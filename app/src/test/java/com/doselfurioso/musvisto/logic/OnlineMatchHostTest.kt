package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.RoomSeat
import com.doselfurioso.musvisto.model.Rooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Arranque host-side online ([OnlineMatchHost.start]): apertura = mus corrido (#17) y mano
 * de apertura ALEATORIA (no siempre el host). Con 4 humanos el bucle se asienta sin avanzar
 * (no hay IA a la que conducir), así que la vista publicada es el estado de apertura.
 */
class OnlineMatchHostTest {

    private class FakeTransport : MatchTransport {
        val lastView = mutableMapOf<String, GameState>()
        override fun publishView(seatId: String, view: GameState) { lastView[seatId] = view }
        override fun observeCommands(onCommand: (String, GameCommand) -> Unit) = Unit
        override fun sendCommand(seatId: String, command: GameCommand) = Unit
        override fun observeView(seatId: String, onView: (GameState) -> Unit) = Unit
    }

    private fun humanSeats(): List<RoomSeat> = Rooms.SEAT_IDS.map { id ->
        RoomSeat(seatId = id, team = Rooms.teamFor(id), uid = "u-$id", displayName = id, connected = true)
    }

    private fun openingView(seed: Long): GameState {
        val transport = FakeTransport()
        OnlineMatchHost(
            gameLogic = MusGameLogic(Random(seed)),
            transport = transport,
            scope = CoroutineScope(Dispatchers.Unconfined),
            rng = Random(seed)
        ).start(humanSeats(), GameSettings())
        return transport.lastView.getValue("p1")
    }

    @Test
    fun `la apertura online corre el mus (musCorrido = true)`() {
        assertTrue(openingView(seed = 1L).musCorrido)
    }

    @Test
    fun `la mano de apertura es aleatoria sembrada, no siempre el host`() {
        // El host consume su rng PRIMERO para la mano → replicable con el mismo seed.
        val seed = 7L
        val expected = Rooms.SEAT_IDS[Random(seed).nextInt(Rooms.SEAT_IDS.size)]
        assertEquals(expected, openingView(seed).manoPlayerId)
    }
}
