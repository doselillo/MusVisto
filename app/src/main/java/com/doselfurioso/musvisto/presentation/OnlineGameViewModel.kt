package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.logic.FirebaseMatchTransport
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.logic.LobbyService
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.logic.OnlineMatchHost
import com.doselfurioso.musvisto.logic.PresenceService
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LastActionInfo
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.doselfurioso.musvisto.model.ScoreBreakdown
import com.doselfurioso.musvisto.model.Suit
import com.doselfurioso.musvisto.model.toAction
import com.doselfurioso.musvisto.model.toCommand
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val presence = PresenceService()

    private val _view = MutableStateFlow<GameState?>(null)
    private val _selectedCards = MutableStateFlow<Set<Card>>(emptySet())
    private val _isSelectingBet = MutableStateFlow(false)

    // Presencia en la mesa: asientos HUMANOS marcados como caídos en la sala. El SDK de RTDB
    // re-sincroniza la vista solos al reconectar; esto es solo para PINTAR quién está caído.
    private val _offlineSeats = MutableStateFlow<Set<String>>(emptySet())
    val offlineSeats: StateFlow<Set<String>> = _offlineSeats.asStateFlow()
    private var roomObserver: ValueEventListener? = null

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
        // Presencia: anuncia MI asiento como conectado (re-arma el onDisconnect que dejó el
        // lobby) y observa la sala para saber qué humanos están caídos (indicador en la mesa).
        presence.attach(roomId, mySeatId)
        roomObserver = lobby.observeRoom(roomId) { snapshot ->
            _offlineSeats.value = offlineHumanSeatIds(snapshot)
        }
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
            // Seña (Fase 4.3): el host la computa VERAZ de mi mano y la pacea/redacta.
            is GameAction.ShowGesture -> send(GameCommand.ShowGesture)
            // Online sin pausa: se ignora (el "Salir" lo pone la pantalla).
            is GameAction.TogglePauseMenu -> Unit
            else -> action.toCommand(_selectedCards.value)?.let { send(it) }
        }
    }

    /**
     * ¿Mi mano da para seña? (Fase 4.3). Gatea el botón "Seña" online. Solo para MI asiento:
     * mi mano sí viaja en la vista redactada (las ajenas son placeholders → no consultar).
     */
    fun hasShowableGesture(player: Player): Boolean =
        player.id == mySeatId && gameLogic.determineGesture(player.hand) != null

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

    override fun onCleared() {
        // Salir de la mesa = salir de la sala: baja el flag de presencia y suelta el observador.
        presence.goOffline()
        roomObserver?.let { lobby.stopObserving(roomId, it) }
        roomObserver = null
        super.onCleared()
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
    // Avatar: el host manda avatarResId=0 (los recursos Android no viajan por red);
    // asignamos uno por asiento (índice) desde el roster — consistente en todos los
    // clientes y, sobre todo, evita painterResource(0) → crash en PlayerAvatar.
    // Manos ajenas: la redacción las vacía → rellenar a 4 reversos (las composables de
    // mano dibujan por cards.size); en el enseñe (revealAllHands) llegan reales.
    val players = view.players.mapIndexed { index, p ->
        val avatar = CharacterRoster.all[index % CharacterRoster.all.size].avatarResId
        val hand = if (!view.revealAllHands && p.id != mySeatId && p.hand.isEmpty()) {
            ONLINE_PLACEHOLDER_HAND
        } else {
            p.hand
        }
        p.copy(avatarResId = avatar, hand = hand)
    }
    // Anuncio sobre el avatar: el host manda solo la ÚLTIMA acción (lastActionView); el
    // historial del lance (currentLanceActions) es @Transient y no viaja.
    val lanceActions = view.lastActionView?.let { last ->
        mapOf(last.seatId to LastActionInfo(playerId = last.seatId, action = last.command.toAction()))
    }.orEmpty()
    val display = view.copy(
        players = players,
        availableActions = view.availableCommands.map { it.toAction() },
        selectedCardsForDiscard = selectedCards,
        isSelectingBet = isSelectingBet,
        currentLanceActions = lanceActions
    )
    // Perspectiva de EQUIPO: la mesa (componentes del modo local) asume teamA = NOSOTROS
    // (verde) / teamB = ELLOS (rojo). Si MI asiento es teamB, intercambio los campos que se
    // pintan por equipo para que MI equipo salga como "NOSOTROS" en todos los clientes (no
    // solo en el host). Es solo display: lo que mando al host no depende del equipo.
    val myTeam = view.players.firstOrNull { it.id == mySeatId }?.team
    return if (myTeam == TEAM_B) display.swapTeamsForDisplay() else display
}

/**
 * Asientos HUMANOS marcados como caídos en la sala (uid presente, no IA, `connected == false`).
 * Puro/testeable: el indicador de presencia en la mesa atenúa + marca estos asientos. Una IA o un
 * asiento vacío nunca cuenta como caído. `null` (sala ilegible) → vacío (no marca a nadie).
 */
internal fun offlineHumanSeatIds(room: RoomSnapshot?): Set<String> =
    room?.seats
        ?.filter { it.uid != null && !it.isAi && !it.connected }
        ?.map { it.seatId }
        ?.toSet()
        .orEmpty()

private const val TEAM_A = "teamA"
private const val TEAM_B = "teamB"

private fun otherTeam(team: String?): String? = when (team) {
    TEAM_A -> TEAM_B
    TEAM_B -> TEAM_A
    else -> team
}

private fun Map<String, Int>.swappedTeams(): Map<String, Int> =
    mapOf(TEAM_A to (this[TEAM_B] ?: 0), TEAM_B to (this[TEAM_A] ?: 0))

/** Intercambia teamA↔teamB en los campos que la mesa pinta por equipo (solo para mostrar). */
private fun GameState.swapTeamsForDisplay(): GameState = copy(
    score = score.swappedTeams(),
    chicosWon = chicosWon.swappedTeams(),
    winningTeam = otherTeam(winningTeam),
    chicoJustWon = otherTeam(chicoJustWon),
    scoreBreakdown = scoreBreakdown?.let {
        ScoreBreakdown(teamAScoreDetails = it.teamBScoreDetails, teamBScoreDetails = it.teamAScoreDetails)
    },
    roundHistory = roundHistory.map { it.copy(winningTeam = otherTeam(it.winningTeam)) }
)

/** 4 cartas "dummy" para dibujar reversos de manos ajenas (nunca se muestran de cara). */
private val ONLINE_PLACEHOLDER_HAND: List<Card> = List(4) { Card(Suit.OROS, Rank.AS) }
