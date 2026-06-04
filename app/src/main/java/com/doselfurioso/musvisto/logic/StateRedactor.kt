package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.ActiveGestureInfo
import com.doselfurioso.musvisto.model.GameState

/**
 * Frontera de información del multijugador (Fase 0.3): "fog of war".
 *
 * En el modelo host-autoritativo el host tiene el [GameState] COMPLETO (las 4
 * manos + el mazo). Antes de mandar el estado a cada cliente lo pasa por
 * [redactFor] con el asiento de ese cliente: borra lo que ese asiento NO puede
 * ver. Función **pura y aditiva** — hoy no está cableada al render (el modo local
 * ve todo dibujando, no partiendo los datos); es el primitivo que consumirá la
 * sincronización en partida (Fase 3).
 *
 * Qué se OCULTA a `seatId`:
 *  - la mano de los DEMÁS jugadores —incluido el compañero: en el Mus no se ve, se
 *    señaliza— salvo en el enseñe final (`revealAllHands == true`).
 *  - el mazo por repartir (`deck`).
 *  - **las señas (Fase 4.2):** `knownGestures`/`pendingGestures` son estado INTERNO
 *    del host (la "memoria" de la IA + su pre-decisión) → nunca viajan al cliente.
 *    `activeGesture` (la seña que se está mostrando) se filtra por asiento: la propia
 *    o la del compañero SIEMPRE; la de un rival solo si este asiento la CAZA (prob
 *    fija 0.20, mismo predicado que la IA en [GestureVisibility]); si no, se borra
 *    (ni flash). Sin esta redacción un cliente tramposo leería la seña rival en la
 *    RTDB cruda — es la frontera anti-trampa de las señas.
 *
 * Lo demás (marcador, turno, fase, apuestas, descartes contados, historial…) es
 * información PÚBLICA y se conserva. Los campos `@Transient` (p. ej. `availableActions`)
 * no viajan por la red: el host los recalcula por asiento.
 */
object StateRedactor {

    fun redactFor(seatId: String, state: GameState): GameState {
        val visibleGesture = redactGesture(seatId, state)
        if (state.revealAllHands) {
            return state.copy(
                deck = emptyList(),
                activeGesture = visibleGesture,
                knownGestures = emptyMap(),
                pendingGestures = emptyMap()
            )
        }
        val redactedPlayers = state.players.map { player ->
            if (player.id == seatId) player else player.copy(hand = emptyList())
        }
        return state.copy(
            players = redactedPlayers,
            deck = emptyList(),
            activeGesture = visibleGesture,
            knownGestures = emptyMap(),
            pendingGestures = emptyMap()
        )
    }

    /**
     * ¿Ve [seatId] la seña activa? La propia o la del compañero, SIEMPRE; la de un
     * rival, solo si la CAZA ([GestureVisibility.HUMAN_INTERCEPT_PROB]) → si no, se
     * borra (ni flash). Devuelve la seña a publicar para ese asiento, o null.
     */
    private fun redactGesture(seatId: String, state: GameState): ActiveGestureInfo? {
        val gesture = state.activeGesture ?: return null
        val gesturer = state.players.find { it.id == gesture.playerId } ?: return null
        val observer = state.players.find { it.id == seatId } ?: return null
        // Mi equipo (yo o mi pareja): siempre visible.
        if (gesturer.team == observer.team) return gesture
        // Rival: solo si lo cazo (mismo predicado/seed que la IA, prob humana fija).
        return if (GestureVisibility.perceivesOpponentSign(
                manoPlayerId = state.manoPlayerId,
                gesturerId = gesture.playerId,
                observerId = seatId,
                kind = gesture.gestureKind,
                prob = GestureVisibility.HUMAN_INTERCEPT_PROB
            )
        ) {
            gesture
        } else {
            null
        }
    }
}
