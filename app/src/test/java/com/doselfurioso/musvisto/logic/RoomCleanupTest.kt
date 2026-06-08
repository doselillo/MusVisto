package com.doselfurioso.musvisto.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selección de salas CADUCADAS para el barrido por edad (RGPD: las salas no se quedan para
 * siempre en RTDB). El criterio es el MISMO que aplican las reglas con la hora del servidor;
 * aquí se prueba la decisión pura del cliente. Ver [staleRoomEntries] / [LobbyService.purgeStaleRooms].
 */
class RoomCleanupTest {

    private val now = 1_000_000_000_000L // hora "actual" fija
    private val sixHours = 6L * 60 * 60 * 1000

    private fun entry(id: String, createdAt: Long, code: String = id) =
        RoomIndexEntry(roomId = id, createdAt = createdAt, code = code)

    @Test
    fun `una sala anterior al umbral es caducada`() {
        val old = entry("OLD", now - sixHours - 1)
        assertEquals(listOf(old), staleRoomEntries(listOf(old), now, sixHours))
    }

    @Test
    fun `una sala reciente NO es caducada`() {
        val fresh = entry("FRESH", now - 1000) // hace 1 segundo
        assertTrue(staleRoomEntries(listOf(fresh), now, sixHours).isEmpty())
    }

    @Test
    fun `justo en el umbral NO se borra (estricto menor que)`() {
        val borderline = entry("EDGE", now - sixHours) // exactamente el umbral
        assertTrue(staleRoomEntries(listOf(borderline), now, sixHours).isEmpty())
    }

    @Test
    fun `createdAt cero o ausente cuenta como antiquisima`() {
        val legacy = entry("ZERO", 0L)
        assertEquals(listOf(legacy), staleRoomEntries(listOf(legacy), now, sixHours))
    }

    @Test
    fun `filtra solo las caducadas de una lista mixta y conserva su codigo`() {
        val old1 = entry("OLD1", now - sixHours - 1, code = "AAAA")
        val fresh = entry("FRESH", now - 1000, code = "BBBB")
        val old2 = entry("OLD2", 0L, code = "CCCC")
        val stale = staleRoomEntries(listOf(old1, fresh, old2), now, sixHours)
        assertEquals(listOf(old1, old2), stale)
        assertEquals(listOf("AAAA", "CCCC"), stale.map { it.code })
    }

    @Test
    fun `lista vacia no devuelve nada`() {
        assertTrue(staleRoomEntries(emptyList(), now, sixHours).isEmpty())
    }
}
