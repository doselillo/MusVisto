package com.doselfurioso.musvisto.logic

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Presencia online (Fase 3/5 — reconexión, docs/context/MULTIPLAYER_PLAN.md): mantiene
 * `rooms/{roomId}/seats/{seatId}/connected` reflejando si ESTE cliente está realmente
 * conectado, con el patrón estándar de Firebase.
 *
 * Escucha el pseudo-nodo `.info/connected` (lo mantiene el SDK: `true` mientras hay socket
 * con RTDB) y, cada vez que pasa a conectado:
 *  1. registra un `onDisconnect` que pondrá `connected = false` cuando el socket caiga —lo
 *     ejecuta el SERVIDOR, así que cubre cierre de app / pérdida de red / crash—, y
 *  2. escribe `connected = true`.
 * Al reconectar, `.info/connected` re-emite `true` y se RE-ARMA el hook (Firebase los
 * consume al dispararse). El orden (onDisconnect ANTES del `true`) cubre una caída justo
 * tras conectar.
 *
 * El impacto JUGABLE de una caída ya lo cubre el turn timer host-side (un humano AFK no
 * congela la mesa, ver [MatchHostService]); esto es la capa de PRESENCIA —que los demás
 * VEAN quién está conectado, en el lobby y en la mesa— y la reconexión limpia del flag.
 * No toca el bucle del host.
 */
class PresenceService(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) {
    private val connectedInfoRef: DatabaseReference = database.getReference(".info/connected")
    private var infoListener: ValueEventListener? = null
    private var seatConnectedRef: DatabaseReference? = null

    /**
     * Empieza a anunciar presencia para [seatId] en [roomId]. Idempotente: un re-attach
     * suelta el listener anterior antes de montar el nuevo (sin tocar el flag, igual que
     * [detach]).
     */
    fun attach(roomId: String, seatId: String) {
        detach()
        val ref = database.reference
            .child("rooms").child(roomId)
            .child("seats").child(seatId).child("connected")
        seatConnectedRef = ref
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (!connected) return // sin socket no podemos escribir; el onDisconnect server-side ya bajó el flag
                ref.onDisconnect().setValue(false)
                ref.setValue(true)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        infoListener = listener
        connectedInfoRef.addValueEventListener(listener)
    }

    /**
     * Deja de anunciar SIN tocar el flag ni el onDisconnect (traspaso lobby→partida: el
     * siguiente VM re-arma con su propio [attach]; el flag sigue `true` y el hook armado →
     * sin parpadeo y la caída en el hueco queda cubierta).
     */
    fun detach() {
        infoListener?.let { connectedInfoRef.removeEventListener(it) }
        infoListener = null
        seatConnectedRef = null
    }

    /**
     * Salida EXPLÍCITA de la sala (botón "Salir"): cancela el onDisconnect y baja el flag ya,
     * para que el asiento no quede marcado como conectado hasta que el SO mate el proceso.
     */
    fun goOffline() {
        val ref = seatConnectedRef
        infoListener?.let { connectedInfoRef.removeEventListener(it) }
        infoListener = null
        seatConnectedRef = null
        if (ref != null) {
            ref.onDisconnect().cancel()
            ref.setValue(false)
        }
    }
}
