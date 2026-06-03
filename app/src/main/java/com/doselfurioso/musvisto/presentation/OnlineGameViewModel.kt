package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.logic.FirebaseMatchTransport
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.logic.LobbyService
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.logic.OnlineMatchHost
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LastActionInfo
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit
import com.doselfurioso.musvisto.model.toAction
import com.doselfurioso.musvisto.model.toCommand
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel de la PARTIDA online (Fase 3c — Slice B). El cliente renderiza la MISMA
 * mesa que el modo local ([GameTable]), alimentada por la vista redactada que publica
 * el host. Este VM es el ADAPTADOR entre el protocolo serializable del host y el
 * `GameState` rico que espera `GameTable` (ver [adaptOnlineView]):
 *  - `availableCommands` (lo que viaja) → `availableActions` (lo que pinta la botonera).
 *  - manos ajenas vacías (redactadas) → 4 cartas placeholder para que se dibujen los
 *    reversos (las composables de mano dibujan por `cards.size`).
 *  - `lastActionView` → `currentLanceActions` (anuncio sobre el avatar).
 *  - estado de UI LOCAL (selección de descarte, selector de envite) que el host no
 *    conoce, inyectado en la vista que se pinta.
 *
 * Las acciones de UI que NO viajan (abrir/cerrar el selector de envite) se resuelven
 * aquí; el resto se mandan como `GameCommand` por el transporte. Sin señas (Fase 4) ni
 * pausa online (el "Salir" lo pone la pantalla). El host (si lo es este cliente) arranca
 * además [OnlineMatchHost].
 */
class OnlineGameViewModel(
    private val roomId: String,
    val mySeatId: String,
    val isHost: Boolean,
    private val gameLogic: MusGameLogic,
    private val lobby: LobbyService,
    private val store: GameStore,
    database: FirebaseDatabase = FirebaseDatabase.getInstance()
) : ViewModel() {

    private val transport = FirebaseMatchTransport(
        database.reference.child("rooms").child(roomId)
    )

    private val _view = MutableStateFlow<GameState?>(null)
    private val _selectedCards = MutableStateFlow<Set<Card>>(emptySet())
    private val _isSelectingBet = MutableStateFlow(false)

    /** Vista lista para pintar con [GameTable]: la del host + estado de UI local, adaptada. */
    val displayState: StateFlow<GameState?> =
        combine(_view, _selectedCards, _isSelectingBet) { view, selected, selectingBet ->
            view?.let { adaptOnlineView(it, mySeatId, selected, selectingBet) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var host: OnlineMatchHost? = null

    init {
        // Las callbacks de Firebase llegan en el hilo principal → actualizar el
        // StateFlow directo es seguro.
        transport.observeView(mySeatId) { state -> _view.value = state }
        if (isHost) startHost()
    }

    /**
     * Acción de la mesa (siempre de MI asiento). Las acciones de UI que no pasan por el
     * reducer se resuelven localmente (selector de envite); el resto se mandan al host.
     */
    fun onAction(action: GameAction) {
        when (action) {
            is GameAction.ToggleBetSelector -> _isSelectingBet.value = true
            is GameAction.CancelBetSelection -> _isSelectingBet.value = false
            is GameAction.Envido -> {
                _isSelectingBet.value = false
                send(GameCommand.Bet(action.amount))
            }
            is GameAction.ConfirmDiscard -> {
                send(GameCommand.Discard(_selectedCards.value.toList()))
                _selectedCards.value = emptySet()
            }
            // Online aún sin pausa ni señas (Fase 4): se ignoran.
            is GameAction.TogglePauseMenu, is GameAction.ShowGesture -> Unit
            else -> action.toCommand(_selectedCards.value)?.let { send(it) }
        }
    }

    /** Alterna la selección de una carta para el descarte (estado local del cliente). */
    fun onCardSelected(card: Card) {
        _selectedCards.value = _selectedCards.value.let { if (card in it) it - card else it + card }
    }

    private fun send(command: GameCommand) = transport.sendCommand(mySeatId, command)

    private fun startHost() {
        lobby.fetchRoom(roomId) { room ->
            room ?: return@fetchRoom
            host = OnlineMatchHost(gameLogic, transport, viewModelScope, log = { Log.w(MP_TAG, it) }).also {
                it.start(room.seats, store.loadSettings())
            }
        }
    }

    private companion object {
        const val MP_TAG = "MusVistoMP"
    }
}

/**
 * Adapta la vista redactada del host al `GameState` que pinta [GameTable]. Función PURA
 * (testeable). Ver [OnlineGameViewModel].
 */
internal fun adaptOnlineView(
    view: GameState,
    mySeatId: String,
    selectedCards: Set<Card>,
    isSelectingBet: Boolean
): GameState {
    // Manos ajenas: la redacción las vacía; rellenar a 4 placeholder para que se dibujen
    // 4 reversos (las composables de mano dibujan por cards.size). En el enseñe
    // (revealAllHands) llegan las manos reales → no se tocan.
    val players = if (view.revealAllHands) {
        view.players
    } else {
        view.players.map { p ->
            if (p.id != mySeatId && p.hand.isEmpty()) p.copy(hand = ONLINE_PLACEHOLDER_HAND) else p
        }
    }
    // Anuncio sobre el avatar: el host manda solo la ÚLTIMA acción (lastActionView); el
    // historial del lance (currentLanceActions) es @Transient y no viaja.
    val lanceActions = view.lastActionView?.let { last ->
        mapOf(last.seatId to LastActionInfo(playerId = last.seatId, action = last.command.toAction()))
    }.orEmpty()
    return view.copy(
        players = players,
        availableActions = view.availableCommands.map { it.toAction() },
        selectedCardsForDiscard = selectedCards,
        isSelectingBet = isSelectingBet,
        currentLanceActions = lanceActions
    )
}

/** 4 cartas "dummy" para dibujar reversos de manos ajenas (nunca se muestran de cara). */
private val ONLINE_PLACEHOLDER_HAND: List<Card> = List(4) { Card(Suit.OROS, Rank.AS) }
