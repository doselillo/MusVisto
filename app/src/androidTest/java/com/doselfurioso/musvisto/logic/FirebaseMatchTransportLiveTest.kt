package com.doselfurioso.musvisto.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.google.firebase.database.FirebaseDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Prueba EN VIVO del circuito de red de Fase 1 contra la RTDB **real**. Necesita:
 *  - emulador/dispositivo con **Google Play services** (para Firebase Auth),
 *  - reglas publicadas (test mode o `auth != null`; firmamos de todos modos).
 *
 * Firma anónima → ida y vuelta de **vista** y **comando** por
 * [FirebaseMatchTransport] sobre una sala desechable que se borra al terminar.
 * Es la contraparte viva de `MatchHostServiceTest` (que corre el bucle en memoria).
 */
@RunWith(AndroidJUnit4::class)
class FirebaseMatchTransportLiveTest {

    private val roomId = "smoketest-${Random.nextInt(0, Int.MAX_VALUE)}"
    private val roomRef = FirebaseDatabase.getInstance().getReference("rooms").child(roomId)

    @Before
    fun signInAnonymously() {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        FirebaseAuthGateway().ensureSignedIn { result ->
            result.onFailure { failure = it }
            latch.countDown()
        }
        assertTrue("Timeout iniciando sesión anónima", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("Auth anónimo falló: ${it.message}", it) }
    }

    @After
    fun cleanup() {
        roomRef.removeValue()
    }

    @Test
    fun laVistaViajaPorRtdb() {
        val transport = FirebaseMatchTransport(roomRef)
        val latch = CountDownLatch(1)
        var received: GameState? = null
        transport.observeView("p1") { view ->
            received = view
            latch.countDown()
        }

        transport.publishView("p1", sampleState())

        assertTrue("Timeout esperando la vista de p1", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(GamePhase.GRANDE, received?.gamePhase)
        assertEquals("p1", received?.currentTurnPlayerId)
        assertEquals(1, received?.players?.size)
    }

    @Test
    fun elComandoViajaPorRtdb() {
        val transport = FirebaseMatchTransport(roomRef)
        val latch = CountDownLatch(1)
        var seat: String? = null
        var command: GameCommand? = null
        transport.observeCommands { seatId, cmd ->
            seat = seatId
            command = cmd
            latch.countDown()
        }

        transport.sendCommand("p1", GameCommand.Bet(amount = 2))

        assertTrue("Timeout esperando el comando", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("p1", seat)
        assertEquals(GameCommand.Bet(amount = 2), command)
    }

    private fun sampleState() = GameState(
        players = listOf(Player(id = "p1", name = "A1", avatarResId = 0, team = "teamA")),
        gamePhase = GamePhase.GRANDE,
        currentTurnPlayerId = "p1",
        manoPlayerId = "p1"
    )

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
