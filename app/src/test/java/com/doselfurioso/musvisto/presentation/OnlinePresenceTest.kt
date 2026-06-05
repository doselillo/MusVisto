package com.doselfurioso.musvisto.presentation

import com.doselfurioso.musvisto.model.RoomSeat
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.doselfurioso.musvisto.model.Rooms
import org.junit.Assert.assertEquals
import org.junit.Test

/** Presencia online: qué asientos pinta la mesa como caídos (ver [offlineHumanSeatIds]). */
class OnlinePresenceTest {

    private fun seat(
        id: String,
        uid: String? = null,
        isAi: Boolean = false,
        connected: Boolean = true
    ) = RoomSeat(
        seatId = id,
        team = Rooms.teamFor(id),
        uid = uid,
        isAi = isAi,
        connected = connected
    )

    private fun room(vararg seats: RoomSeat) =
        RoomSnapshot(roomId = "r", code = "ABCD", hostUid = "h", status = Rooms.STATUS_PLAYING, seats = seats.toList())

    @Test
    fun `solo cuenta humanos con connected=false`() {
        val out = offlineHumanSeatIds(
            room(
                seat("p1", uid = "h", connected = true),
                seat("p2", uid = "u2", connected = false),
                seat("p3", isAi = true, connected = false), // IA: nunca cuenta como caída
                seat("p4", uid = "u4", connected = false)
            )
        )
        assertEquals(setOf("p2", "p4"), out)
    }

    @Test
    fun `todos conectados (o IA) devuelve vacio`() {
        val out = offlineHumanSeatIds(
            room(
                seat("p1", uid = "h", connected = true),
                seat("p2", uid = "u2", connected = true),
                seat("p3", isAi = true, connected = false),
                seat("p4") // asiento vacío: sin uid, no cuenta
            )
        )
        assertEquals(emptySet<String>(), out)
    }

    @Test
    fun `sala nula devuelve vacio`() {
        assertEquals(emptySet<String>(), offlineHumanSeatIds(null))
    }
}
