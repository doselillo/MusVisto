package com.doselfurioso.musvisto.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.RoomHandle
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.google.firebase.database.FirebaseDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prueba EN VIVO del lobby (Fase 2) contra la RTDB real: el host crea una sala y
 * un segundo jugador se une por código → cada uno reclama su asiento. Limpia la
 * sala al terminar. Necesita emulador con Google Play services + reglas auth.
 */
@RunWith(AndroidJUnit4::class)
class LobbyServiceLiveTest {

    private val lobby = LobbyService()
    private var hostUid: String = ""
    private var created: RoomHandle? = null

    @Before
    fun signIn() {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        FirebaseAuthGateway().ensureSignedIn { result ->
            result.onSuccess { hostUid = it }.onFailure { failure = it }
            latch.countDown()
        }
        assertTrue("Timeout en Auth", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("Auth falló: ${it.message}", it) }
    }

    @After
    fun cleanup() {
        val handle = created ?: return
        FirebaseDatabase.getInstance().reference.updateChildren(
            mapOf("rooms/${handle.roomId}" to null, "codes/${handle.code}" to null)
        )
    }

    @Test
    fun crearYUnirseReclamaAsientosDistintos() {
        val host = createRoom()
        created = host
        assertEquals("el host reclama p1", "p1", host.seatId)

        val joiner = joinRoom(host.code)
        assertEquals("el segundo entra en p2", "p2", joiner.seatId)

        val room = fetchRoom(host.roomId)
        assertNotNull("la sala debe existir", room)
        val seats = room!!.seats.associateBy { it.seatId }
        assertEquals(hostUid, seats.getValue("p1").uid)
        assertEquals(JOINER_UID, seats.getValue("p2").uid)
        assertEquals("teamA", seats.getValue("p1").team)
        assertEquals("teamB", seats.getValue("p2").team)
        assertTrue("p3 sigue vacío", seats.getValue("p3").isEmpty)
        assertTrue("p4 sigue vacío", seats.getValue("p4").isEmpty)
    }

    private fun createRoom(): RoomHandle {
        val latch = CountDownLatch(1)
        var handle: RoomHandle? = null
        var failure: Throwable? = null
        lobby.createRoom(hostUid, "Host", GameSettings()) { result ->
            result.onSuccess { handle = it }.onFailure { failure = it }
            latch.countDown()
        }
        assertTrue("Timeout creando sala", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("createRoom falló: ${it.message}", it) }
        return handle!!
    }

    private fun joinRoom(code: String): RoomHandle {
        val latch = CountDownLatch(1)
        var handle: RoomHandle? = null
        var failure: Throwable? = null
        lobby.joinRoom(code, JOINER_UID, "Invitado") { result ->
            result.onSuccess { handle = it }.onFailure { failure = it }
            latch.countDown()
        }
        assertTrue("Timeout uniéndose", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("joinRoom falló: ${it.message}", it) }
        return handle!!
    }

    private fun fetchRoom(roomId: String): RoomSnapshot? {
        val latch = CountDownLatch(1)
        var snapshot: RoomSnapshot? = null
        lobby.fetchRoom(roomId) { snapshot = it; latch.countDown() }
        assertTrue("Timeout leyendo sala", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return snapshot
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        const val JOINER_UID = "joiner-uid-test"
    }
}
