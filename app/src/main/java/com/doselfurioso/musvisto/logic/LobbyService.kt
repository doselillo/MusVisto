package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.RoomHandle
import com.doselfurioso.musvisto.model.RoomSeat
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.doselfurioso.musvisto.model.Rooms
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Crea, une y observa SALAS de multijugador en Realtime Database (Fase 2 del
 * plan, docs/context/MULTIPLAYER_PLAN.md). Opera sobre la raíz de la base
 * (`rooms/{roomId}` + índice `codes/{code} → roomId`).
 *
 * El reparto de turnos del juego NO vive aquí (eso es [FirebaseMatchTransport] +
 * [MatchHostService]); este servicio es solo el LOBBY: presencia de asientos,
 * códigos y arranque. Todas las operaciones exigen sesión ([FirebaseAuthGateway]);
 * las reglas de RTDB rechazan a un cliente sin `auth`.
 */
class LobbyService(
    private val root: DatabaseReference = FirebaseDatabase.getInstance().reference
) {
    private val json = Json { encodeDefaults = true }

    private val roomsRef: DatabaseReference get() = root.child("rooms")
    private val codesRef: DatabaseReference get() = root.child("codes")

    /**
     * Crea una sala: el host reclama p1, los otros tres asientos quedan VACÍOS
     * (los rellenará con IA o se unirán humanos). Escribe meta + asientos + el
     * índice de código en una sola actualización atómica desde la raíz.
     */
    fun createRoom(
        hostUid: String,
        hostName: String,
        settings: GameSettings,
        onResult: (Result<RoomHandle>) -> Unit
    ) {
        val roomId = roomsRef.push().key
        if (roomId == null) {
            onResult(Result.failure(IllegalStateException("RTDB no devolvió roomId")))
            return
        }
        val code = generateCode()
        val updates = hashMapOf<String, Any?>(
            "rooms/$roomId/meta/hostUid" to hostUid,
            "rooms/$roomId/meta/status" to Rooms.STATUS_LOBBY,
            "rooms/$roomId/meta/code" to code,
            "rooms/$roomId/meta/createdAt" to ServerValue.TIMESTAMP,
            "rooms/$roomId/meta/settingsJson" to json.encodeToString(settings),
            "codes/$code" to roomId
        )
        Rooms.SEAT_IDS.forEach { seatId ->
            updates["rooms/$roomId/seats/$seatId"] = seatNode(
                seatId = seatId,
                uid = if (seatId == HOST_SEAT) hostUid else null,
                displayName = if (seatId == HOST_SEAT) hostName else "",
                connected = seatId == HOST_SEAT
            )
        }
        root.updateChildren(updates)
            .addOnSuccessListener { onResult(Result.success(RoomHandle(roomId, code, HOST_SEAT))) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    /**
     * Une a una sala por [code]: resuelve el código → roomId y reclama el primer
     * asiento vacío con una TRANSACCIÓN (dos clientes simultáneos no pueden
     * quedarse el mismo asiento). Falla si el código no existe o la sala está llena.
     */
    fun joinRoom(
        code: String,
        uid: String,
        displayName: String,
        onResult: (Result<RoomHandle>) -> Unit
    ) {
        val normalized = code.trim().uppercase()
        codesRef.child(normalized).get()
            .addOnSuccessListener { snap ->
                val roomId = snap.getValue(String::class.java)
                if (roomId == null) {
                    onResult(Result.failure(NoSuchElementException("Sala $normalized no encontrada")))
                } else {
                    claimSeat(roomId, normalized, uid, displayName, onResult)
                }
            }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    private fun claimSeat(
        roomId: String,
        code: String,
        uid: String,
        displayName: String,
        onResult: (Result<RoomHandle>) -> Unit
    ) {
        roomsRef.child(roomId).child("seats").runTransaction(object : Transaction.Handler {
            private var claimedSeat: String? = null

            override fun doTransaction(currentData: MutableData): Transaction.Result {
                for (seatId in Rooms.SEAT_IDS) {
                    val seat = currentData.child(seatId)
                    val seatUid = seat.child("uid").getValue(String::class.java)
                    val isAi = seat.child("isAi").getValue(Boolean::class.java) ?: false
                    if (seatUid == null && !isAi) {
                        seat.child("uid").value = uid
                        seat.child("displayName").value = displayName
                        seat.child("connected").value = true
                        claimedSeat = seatId
                        return Transaction.success(currentData)
                    }
                }
                return Transaction.abort() // sala llena
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, current: DataSnapshot?) {
                val seat = claimedSeat
                when {
                    error != null -> onResult(Result.failure(error.toException()))
                    !committed || seat == null -> onResult(Result.failure(IllegalStateException("Sala llena")))
                    else -> onResult(Result.success(RoomHandle(roomId, code, seat)))
                }
            }
        })
    }

    /** Lee la sala una vez (snapshot puntual). */
    fun fetchRoom(roomId: String, onResult: (RoomSnapshot?) -> Unit) {
        roomsRef.child(roomId).get()
            .addOnSuccessListener { onResult(parseRoom(roomId, it)) }
            .addOnFailureListener { onResult(null) }
    }

    /**
     * Observa la sala en vivo (para la UI del lobby). Devuelve el listener para
     * poder soltarlo con [stopObserving] al salir.
     */
    fun observeRoom(roomId: String, onUpdate: (RoomSnapshot?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onUpdate(parseRoom(roomId, snapshot))
            override fun onCancelled(error: DatabaseError) = onUpdate(null)
        }
        roomsRef.child(roomId).addValueEventListener(listener)
        return listener
    }

    fun stopObserving(roomId: String, listener: ValueEventListener) {
        roomsRef.child(roomId).removeEventListener(listener)
    }

    // ---- Configuración del host (lobby) ----

    /** El host convierte un asiento en IA con [archetype] (clave de `AIArchetype`). */
    fun setSeatAi(roomId: String, seatId: String, archetype: String) {
        seatRef(roomId, seatId).updateChildren(
            mapOf(
                "isAi" to true,
                "uid" to null,
                "displayName" to "IA",
                "archetype" to archetype,
                "connected" to true,
                "ready" to true // una IA siempre está lista
            )
        )
    }

    /** El host vacía un asiento (quita IA o expulsa). */
    fun clearSeat(roomId: String, seatId: String) {
        seatRef(roomId, seatId).updateChildren(
            mapOf(
                "isAi" to false,
                "uid" to null,
                "displayName" to "",
                "archetype" to null,
                "connected" to false,
                "ready" to false
            )
        )
    }

    /** Marca/desmarca "listo" de un asiento humano. */
    fun setReady(roomId: String, seatId: String, ready: Boolean) {
        seatRef(roomId, seatId).child("ready").setValue(ready)
    }

    /** El host cambia el estado de la sala (lobby → playing → finished). */
    fun setStatus(roomId: String, status: String) {
        roomsRef.child(roomId).child("meta").child("status").setValue(status)
    }

    private fun seatRef(roomId: String, seatId: String): DatabaseReference =
        roomsRef.child(roomId).child("seats").child(seatId)

    private fun parseRoom(roomId: String, snapshot: DataSnapshot): RoomSnapshot? {
        if (!snapshot.exists()) return null
        val meta = snapshot.child("meta")
        val seats = Rooms.SEAT_IDS.map { seatId -> parseSeat(seatId, snapshot.child("seats").child(seatId)) }
        return RoomSnapshot(
            roomId = roomId,
            code = meta.child("code").getValue(String::class.java).orEmpty(),
            hostUid = meta.child("hostUid").getValue(String::class.java).orEmpty(),
            status = meta.child("status").getValue(String::class.java) ?: Rooms.STATUS_LOBBY,
            seats = seats
        )
    }

    private fun parseSeat(seatId: String, seat: DataSnapshot): RoomSeat = RoomSeat(
        seatId = seatId,
        team = seat.child("team").getValue(String::class.java) ?: Rooms.teamFor(seatId),
        uid = seat.child("uid").getValue(String::class.java),
        displayName = seat.child("displayName").getValue(String::class.java).orEmpty(),
        isAi = seat.child("isAi").getValue(Boolean::class.java) ?: false,
        archetype = seat.child("archetype").getValue(String::class.java),
        ready = seat.child("ready").getValue(Boolean::class.java) ?: false,
        connected = seat.child("connected").getValue(Boolean::class.java) ?: false
    )

    private fun seatNode(
        seatId: String,
        uid: String?,
        displayName: String,
        connected: Boolean
    ): Map<String, Any?> = mapOf(
        "team" to Rooms.teamFor(seatId),
        "uid" to uid,
        "displayName" to displayName,
        "isAi" to false,
        "archetype" to null,
        "ready" to false,
        "connected" to connected
    )

    private fun generateCode(): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET.random() }.joinToString("")

    private companion object {
        const val HOST_SEAT = "p1"
        const val CODE_LENGTH = 4
        // Sin 0/O/1/I para que el código sea fácil de dictar.
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
