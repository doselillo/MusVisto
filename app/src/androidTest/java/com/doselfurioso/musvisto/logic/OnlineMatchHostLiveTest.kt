package com.doselfurioso.musvisto.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.RoomSeat
import com.doselfurioso.musvisto.model.Rooms
import com.google.firebase.database.FirebaseDatabase
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Prueba EN VIVO del motor host de Fase 3 contra la RTDB real: una mesa de SOLO IA
 * auto-juega una ronda entera (Mus → lances → ROUND_OVER) a través de
 * [OnlineMatchHost] sobre [FirebaseMatchTransport]. Es la contraparte VIVA del
 * test en memoria `MatchHostServiceTest` (mismo motor, ahora por la red real).
 */
@RunWith(AndroidJUnit4::class)
class OnlineMatchHostLiveTest {

    private val roomId = "match-smoke-${Random.nextInt(0, Int.MAX_VALUE)}"
    private val roomRef = FirebaseDatabase.getInstance().getReference("rooms").child(roomId)

    @Before
    fun signIn() {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        FirebaseAuthGateway().ensureSignedIn { result ->
            result.onFailure { failure = it }
            latch.countDown()
        }
        assertTrue("Timeout en Auth", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        failure?.let { throw AssertionError("Auth falló: ${it.message}", it) }
    }

    @After
    fun cleanup() {
        roomRef.removeValue()
    }

    @Test
    fun mesaDeSoloIaAutojuegaUnaRondaPorFirebase() {
        val seats = Rooms.SEAT_IDS.map { seatId ->
            RoomSeat(
                seatId = seatId,
                team = Rooms.teamFor(seatId),
                isAi = true,
                archetype = "EQUILIBRADO",
                connected = true,
                ready = true
            )
        }
        val transport = FirebaseMatchTransport(roomRef)
        val latch = CountDownLatch(1)
        var finalPhase: GamePhase? = null
        transport.observeView("p1") { view: GameState ->
            if (view.gamePhase == GamePhase.ROUND_OVER || view.gamePhase == GamePhase.GAME_OVER) {
                finalPhase = view.gamePhase
                latch.countDown()
            }
        }

        OnlineMatchHost(MusGameLogic(Random(0)), transport, Random(0)).start(seats, GameSettings())

        assertTrue(
            "La mesa de solo IA debe auto-resolver la ronda y publicar la vista por Firebase",
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertNotNull(finalPhase)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
