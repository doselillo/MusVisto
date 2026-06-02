package com.doselfurioso.musvisto.presentation

import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.AIArchetype
import com.doselfurioso.musvisto.logic.FirebaseAuthGateway
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.logic.LobbyService
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
    private val store: GameStore
) : ViewModel() {

    private val _state = MutableStateFlow(LobbyUiState())
    val state = _state.asStateFlow()

    private var roomObserver: ValueEventListener? = null
    private var observedRoomId: String? = null

    fun createRoom() {
        if (_state.value.phase == LobbyPhase.CONNECTING) return
        _state.update { it.copy(phase = LobbyPhase.CONNECTING, error = null) }
        auth.ensureSignedIn { authResult ->
            authResult.onSuccess { uid ->
                val settings = store.loadSettings()
                lobby.createRoom(uid, settings.humanName, settings) { result ->
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
        _state.update { it.copy(phase = LobbyPhase.CONNECTING, error = null) }
        auth.ensureSignedIn { authResult ->
            authResult.onSuccess { uid ->
                val name = store.loadSettings().humanName
                lobby.joinRoom(trimmed, uid, name) { result ->
                    result.onSuccess { handle -> enterRoom(uid, handle) }.onFailure(::fail)
                }
            }.onFailure(::fail)
        }
    }

    private fun enterRoom(uid: String, handle: RoomHandle) {
        observedRoomId = handle.roomId
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
        detachObserver()
        _state.value = LobbyUiState()
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
        super.onCleared()
    }
}
