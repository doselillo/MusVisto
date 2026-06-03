package com.doselfurioso.musvisto.presentation

import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.FirebaseMatchTransport
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.logic.LobbyService
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.logic.OnlineMatchHost
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel de la PARTIDA online (Fase 3, sub-paso 3b — rebanada MÍNIMA: SOLO
 * LECTURA). Cierra el circuito host↔cliente sobre Firebase ya validado en 3a:
 *
 *  - **Todo cliente** (el host incluido, que también ocupa un asiento) observa su
 *    vista redactada en vivo (`views/{miAsiento}`) y la expone como [view] para
 *    que la UI la pinte. Lo que llega es el [GameState] ya redactado por el host
 *    ([com.doselfurioso.musvisto.logic.StateRedactor]): solo la mano propia tiene
 *    cartas; el mazo y las manos ajenas viajan vacíos.
 *  - **El host** además arranca el motor autoritativo ([OnlineMatchHost]) desde
 *    los asientos de la sala (reparte y corre el bucle de IA/fases de sistema, el
 *    mismo que 3a probó en 2 dispositivos).
 *
 * NO cubierto aquí (sub-paso 3b.2 y 3c): mandar comandos (botones de acción +
 * `availableActions` recalculadas por el host —son `@Transient`, llegan vacías—),
 * transiciones de ronda, pacing/turn timers y desuscripción de los listeners de
 * Firebase al salir (hoy se filtran; aceptable para el flujo hacia adelante de
 * este checkpoint).
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
    val view = _view.asStateFlow()

    private var host: OnlineMatchHost? = null

    init {
        // Las callbacks de Firebase llegan en el hilo principal → actualizar el
        // StateFlow directo es seguro.
        transport.observeView(mySeatId) { state -> _view.value = state }
        if (isHost) startHost()
    }

    /**
     * El humano de este asiento envía su comando al host, que lo consume por
     * `actions/{miAsiento}` y re-publica las vistas. Solo procede en el turno
     * propio: el host solo rellena `availableCommands` del asiento de turno, así
     * que la UI solo ofrece botones cuando toca.
     */
    fun send(command: GameCommand) = transport.sendCommand(mySeatId, command)

    private fun startHost() {
        lobby.fetchRoom(roomId) { room ->
            room ?: return@fetchRoom
            // Settings del dispositivo host = con las que creó la sala. Paso 3c:
            // leerlas de `meta/settingsJson` para robustez si el host las edita
            // entre crear y empezar.
            host = OnlineMatchHost(gameLogic, transport).also {
                it.start(room.seats, store.loadSettings())
            }
        }
    }
}
