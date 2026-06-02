package com.doselfurioso.musvisto.logic

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
 *
 * Lo demás (marcador, turno, fase, apuestas, descartes contados, historial…) es
 * información PÚBLICA y se conserva. Las señas (gestures) son un mecanismo de
 * equipo cuyo modelo de red es la Fase 4; aquí no se tocan. Los campos `@Transient`
 * (p. ej. `availableActions`) no viajan por la red: el host los recalcula por asiento.
 */
object StateRedactor {

    fun redactFor(seatId: String, state: GameState): GameState {
        if (state.revealAllHands) return state.copy(deck = emptyList())
        val redactedPlayers = state.players.map { player ->
            if (player.id == seatId) player else player.copy(hand = emptyList())
        }
        return state.copy(players = redactedPlayers, deck = emptyList())
    }
}
