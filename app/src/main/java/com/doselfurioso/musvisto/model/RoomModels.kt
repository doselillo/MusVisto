package com.doselfurioso.musvisto.model

/**
 * Modelo de la SALA multijugador en Realtime Database (Fase 2 del plan,
 * docs/context/MULTIPLAYER_PLAN.md). Espejo de los nodos `rooms/{roomId}` que
 * lee/escribe [com.doselfurioso.musvisto.logic.LobbyService].
 *
 * La geometría de pareja es FIJA por asiento (teamA = p1+p3, teamB = p2+p4),
 * igual que el modo local → no hay que reinventar parejas.
 */
object Rooms {
    /** Asientos del motor, en orden de turno. Coincide con los ids de `Player`. */
    val SEAT_IDS = listOf("p1", "p2", "p3", "p4")

    const val STATUS_LOBBY = "lobby"
    const val STATUS_PLAYING = "playing"
    const val STATUS_FINISHED = "finished"

    /** Pareja fija por asiento (igual que la mesa local). */
    fun teamFor(seatId: String): String =
        if (seatId == "p1" || seatId == "p3") "teamA" else "teamB"
}

/**
 * Un asiento de la sala. `uid == null && !isAi` = VACÍO (reclamable). Un humano
 * tiene `uid` y `!isAi`; una IA tiene `isAi` y un [archetype] (clave de
 * `AIArchetype`). [team] es fijo por asiento.
 */
data class RoomSeat(
    val seatId: String,
    val team: String,
    val uid: String? = null,
    val displayName: String = "",
    val isAi: Boolean = false,
    val archetype: String? = null,
    val ready: Boolean = false,
    val connected: Boolean = false
) {
    val isEmpty: Boolean get() = uid == null && !isAi
}

/** Vista de la sala para el lobby: meta pública + los cuatro asientos. */
data class RoomSnapshot(
    val roomId: String,
    val code: String,
    val hostUid: String,
    val status: String,
    val seats: List<RoomSeat>
) {
    val isFull: Boolean get() = seats.none { it.isEmpty }
    val allReady: Boolean get() = seats.all { it.ready || it.isAi }
}

/** Identifica al cliente dentro de una sala tras crear/unirse. */
data class RoomHandle(
    val roomId: String,
    val code: String,
    val seatId: String
)
