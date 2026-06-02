package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameCommandCodec
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.GameStateCodec
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

/**
 * Implementación **Firebase Realtime Database** de [MatchTransport] (Fase 1 del
 * plan, docs/context/MULTIPLAYER_PLAN.md). Adaptador FINO: el bucle host↔clientes
 * ([MatchHostService]) ya está probado en memoria (`FakeMatchTransport`); aquí solo
 * se mapean las cuatro operaciones a RTDB y se (de)serializa en la frontera.
 *
 * Nodos bajo [roomRef] = `rooms/{roomId}`:
 *  - `views/{seat}`   ← String JSON del `GameState` redactado (host escribe, cliente lee).
 *  - `actions/{seat}` ← String JSON del `GameCommand` (cliente escribe, host consume y borra).
 *
 * Los payloads se guardan como **String** (no como objetos RTDB) para serializar
 * con kotlinx —jerarquía sellada de `GameCommand`, `@Transient` de `GameState`—
 * sin chocar con el mapeo POJO de Firebase.
 */
class FirebaseMatchTransport(
    private val roomRef: DatabaseReference
) : MatchTransport {

    private val viewsRef: DatabaseReference get() = roomRef.child("views")
    private val actionsRef: DatabaseReference get() = roomRef.child("actions")

    // ---- Lado HOST ----
    override fun publishView(seatId: String, view: GameState) {
        viewsRef.child(seatId).setValue(GameStateCodec.encode(view))
    }

    override fun observeCommands(onCommand: (seatId: String, command: GameCommand) -> Unit) {
        actionsRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) =
                consume(snapshot, onCommand)

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) =
                consume(snapshot, onCommand)

            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    private fun consume(snapshot: DataSnapshot, onCommand: (String, GameCommand) -> Unit) {
        val seatId = snapshot.key ?: return
        val payload = snapshot.getValue(String::class.java) ?: return
        onCommand(seatId, GameCommandCodec.decode(payload))
        snapshot.ref.removeValue() // un solo slot por asiento: consumir y liberar
    }

    // ---- Lado CLIENTE ----
    override fun sendCommand(seatId: String, command: GameCommand) {
        actionsRef.child(seatId).setValue(GameCommandCodec.encode(command))
    }

    override fun observeView(seatId: String, onView: (GameState) -> Unit) {
        viewsRef.child(seatId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val payload = snapshot.getValue(String::class.java) ?: return
                onView(GameStateCodec.decode(payload))
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }
}
