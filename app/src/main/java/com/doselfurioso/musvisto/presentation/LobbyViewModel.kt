package com.doselfurioso.musvisto.presentation

import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.AIArchetype
import com.doselfurioso.musvisto.logic.FirebaseAuthGateway
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.logic.LobbyService
import com.doselfurioso.musvisto.logic.PresenceService
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.RoomHandle
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.doselfurioso.musvisto.model.Rooms
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LobbyPhase { ENTRY, CONNECTING, IN_ROOM }

/**
 * Estado de la pantalla de lobby online. [room] llega del observador en vivo de
 * RTDB; [myHandle]/[myUid] identifican a este cliente para distinguir su asiento
 * y si es el host.
 */
data class LobbyUiState(
    val phase: LobbyPhase = LobbyPhase.ENTRY,
    /** Nombre tecleado en la pantalla de entrada; con el que se entra a la sala. */
    val displayName: String = "",
    val room: RoomSnapshot? = null,
    val myHandle: RoomHandle? = null,
    val myUid: String? = null,
    val error: String? = null
) {
    val mySeatId: String? get() = myHandle?.seatId
    val isHost: Boolean get() = room != null && room.hostUid == myUid
}

/**
 * ViewModel del lobby online (Fase 2): orquesta [FirebaseAuthGateway] +
 * [LobbyService] y expone un [LobbyUiState] que la UI observa. Las callbacks de
 * Firebase llegan en el hilo principal, así que actualizan el StateFlow directo.
 */
class LobbyViewModel(
    private val auth: FirebaseAuthGateway,
    private val lobby: LobbyService,
    private val store: GameStore,
    private val presence: PresenceService = PresenceService()
) : ViewModel() {

    private val _state = MutableStateFlow(LobbyUiState())
    val state = _state.asStateFlow()

    private var roomObserver: ValueEventListener? = null
    private var observedRoomId: String? = null

    init {
        // Precarga el nombre guardado (coherente con el offline). El placeholder
        // por defecto ("Tú") se trata como "sin nombre" → el campo pide uno real.
        _state.update { it.copy(displayName = initialLobbyName(store.loadSettings().humanName)) }
    }

    /** Edita el nombre del campo de entrada; capado para no romper el layout de la mesa. */
    fun setName(name: String) {
        _state.update { it.copy(displayName = name.take(LOBBY_NAME_MAX_LENGTH)) }
    }

    fun createRoom() {
        if (_state.value.phase == LobbyPhase.CONNECTING) return
        val name = commitName()
        _state.update { it.copy(phase = LobbyPhase.CONNECTING, error = null) }
        auth.ensureSignedIn { authResult ->
            authResult.onSuccess { uid ->
                lobby.purgeStaleRooms() // barrido oportunista de salas abandonadas (RGPD)
                lobby.createRoom(uid, name, store.loadSettings()) { result ->
                    result.onSuccess { handle -> enterRoom(uid, handle) }.onFailure(::fail)
                }
            }.onFailure(::fail)
        }
    }

    fun joinRoom(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Escribe un código de sala") }
            return
        }
        if (_state.value.phase == LobbyPhase.CONNECTING) return
        val name = commitName()
        _state.update { it.copy(phase = LobbyPhase.CONNECTING, error = null) }
        auth.ensureSignedIn { authResult ->
            authResult.onSuccess { uid ->
                lobby.purgeStaleRooms() // barrido oportunista de salas abandonadas (RGPD)
                lobby.joinRoom(trimmed, uid, name) { result ->
                    result.onSuccess { handle -> enterRoom(uid, handle) }.onFailure(::fail)
                }
            }.onFailure(::fail)
        }
    }

    /**
     * Nombre con el que entrar a la sala (fallback "Jugador" si se dejó en blanco) y
     * persiste el tecleado no vacío en [GameSettings], para recordarlo y mantenerlo
     * coherente con el offline. Llamar al crear/unirse.
     */
    private fun commitName(): String {
        val typed = _state.value.displayName.trim()
        if (typed.isNotEmpty()) store.saveSettings(store.loadSettings().copy(humanName = typed))
        return effectiveLobbyName(typed)
    }

    private fun enterRoom(uid: String, handle: RoomHandle) {
        observedRoomId = handle.roomId
        // Presencia: anuncia este asiento como conectado y arma el onDisconnect (su flag se
        // baja solo si caemos). Se mantiene viva en el traspaso a la partida (ver onCleared).
        presence.attach(handle.roomId, handle.seatId)
        roomObserver = lobby.observeRoom(handle.roomId) { snapshot ->
            _state.update {
                it.copy(
                    phase = LobbyPhase.IN_ROOM,
                    room = snapshot,
                    myHandle = handle,
                    myUid = uid,
                    error = null
                )
            }
        }
    }

    /** El host añade IA en un asiento vacío (arquetipo inicial = el primero). */
    fun addAi(seatId: String) {
        val state = _state.value
        if (!state.isHost) return
        lobby.setSeatAi(state.room!!.roomId, seatId, AIArchetype.values().first().name)
    }

    /** El host rota la personalidad de un asiento de IA. */
    fun cycleArchetype(seatId: String) {
        val room = _state.value.room ?: return
        if (!_state.value.isHost) return
        val seat = room.seats.firstOrNull { it.seatId == seatId } ?: return
        if (!seat.isAi) return
        val current = seat.archetype?.let { AIArchetype.byName(it) } ?: AIArchetype.values().first()
        lobby.setSeatAi(room.roomId, seatId, current.next().name)
    }

    /** El host vacía un asiento (quita la IA). */
    fun clearSeat(seatId: String) {
        val room = _state.value.room ?: return
        if (!_state.value.isHost) return
        lobby.clearSeat(room.roomId, seatId)
    }

    /** Este jugador marca/desmarca "listo" en su asiento. */
    fun toggleReady() {
        val state = _state.value
        val room = state.room ?: return
        val seatId = state.mySeatId ?: return
        val seat = room.seats.firstOrNull { it.seatId == seatId } ?: return
        lobby.setReady(room.roomId, seatId, !seat.ready)
    }

    /** El host arranca: marca la sala como "playing" (la partida la cablea Fase 3). */
    fun startMatch() {
        val room = _state.value.room ?: return
        if (!_state.value.isHost) return
        lobby.setStatus(room.roomId, Rooms.STATUS_PLAYING)
    }

    fun leaveRoom() {
        val state = _state.value
        detachObserver()
        // Salida explícita: baja el flag de presencia ya (no esperar a que el SO mate el proceso).
        // ANTES de borrar: goOffline cancela el onDisconnect (si no, resucitaría el nodo borrado).
        presence.goOffline()
        // El host se va = la sala muere (es el motor): bórrala para no dejar residuo (RGPD).
        if (state.isHost) state.room?.let { lobby.deleteRoom(it.roomId, it.code) }
        // Conserva el nombre (ya persistido) para no obligar a reescribirlo al volver.
        _state.value = LobbyUiState(displayName = initialLobbyName(store.loadSettings().humanName))
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun detachObserver() {
        val roomId = observedRoomId
        val observer = roomObserver
        if (roomId != null && observer != null) lobby.stopObserving(roomId, observer)
        roomObserver = null
        observedRoomId = null
    }

    private fun fail(error: Throwable) {
        _state.update {
            it.copy(
                phase = if (it.room != null) LobbyPhase.IN_ROOM else LobbyPhase.ENTRY,
                error = error.message ?: "Error de red"
            )
        }
    }

    override fun onCleared() {
        detachObserver()
        // detach NEUTRO (no baja el flag): si esto es el traspaso lobby→partida, el
        // OnlineGameViewModel re-arma la presencia; el flag sigue conectado, sin parpadeo.
        // Una salida explícita ya bajó el flag antes (leaveRoom → goOffline).
        presence.detach()
        super.onCleared()
    }
}

private const val LOBBY_NAME_MAX_LENGTH = 16
private const val LOBBY_FALLBACK_NAME = "Jugador"

/**
 * El nombre guardado para precargar el campo del lobby; cadena vacía si sigue siendo
 * el placeholder offline por defecto ("Tú") → la pantalla pide un nombre real en vez
 * de mostrar el placeholder como si lo fuera.
 */
internal fun initialLobbyName(savedName: String): String {
    val trimmed = savedName.trim()
    return if (trimmed == GameSettings().humanName) "" else trimmed
}

/**
 * Nombre efectivo con el que entrar a la sala: el tecleado (recortado), o "Jugador"
 * si se dejó en blanco. Nunca devuelve "Tú" (el placeholder offline = el bug).
 */
internal fun effectiveLobbyName(typedName: String): String =
    typedName.trim().ifBlank { LOBBY_FALLBACK_NAME }
