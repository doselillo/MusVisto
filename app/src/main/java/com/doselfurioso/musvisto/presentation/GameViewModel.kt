package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.debug.DebugFeatures
import com.doselfurioso.musvisto.logic.AIArchetype
import com.doselfurioso.musvisto.logic.AILogic
import com.doselfurioso.musvisto.logic.AIProfile
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// #20 (pieza C): visibilidad de la seña en pantalla, ASIMÉTRICA. Solo la del
// COMPAÑERO del humano dura 1.5 s (para que él la lea y decida el corte que se
// le delega). La del RIVAL (y la propia) vuelve al flash corto de antes: si
// durara 1.5 s el humano interceptaría señas rivales gratis, rompiendo el
// balance (la IA solo intercepta al 20%, opponentSignPerceived). No afecta a
// la IA ni a esa interceptación (no usan la duración visual).
private const val GESTURE_VISIBLE_PARTNER_MS = 1500L
private const val GESTURE_VISIBLE_OTHER_MS = 300L

// #20 (delegación): probabilidad de que la IA marque pasar seña al entrar al
// Mus. La seña es la puerta tanto del apoyo en envites como de la delegación
// del corte de Mus (AILogic.decideMusDelegation). Con partner humano ~máxima
// — sin seña el humano juega a ciegas y la delegación pierde sentido. Con
// partner IA antes era 0.70 (cuando la delegación de corte estaba acotada a
// humano-only); ahora 0.90: la delegación IA↔IA está activa y un 70% dejaba
// demasiados casos donde el primero IA cortaba con buena mano por no haber
// señalizado.
private const val PENDING_GESTURE_PROB_HUMAN_PARTNER = 0.95f
private const val PENDING_GESTURE_PROB_AI_PARTNER = 0.90f

// Ids de asiento (orden fijo de defaultPlayers): humano p1 + pareja p3 en teamA;
// rivales p4 (izq.) / p2 (der.) en teamB. Mapean cada asiento de IA a su
// arquetipo en GameSettings (profileFor, #34 Fase C).
private const val PLAYER_HUMAN = "p1"
private const val PLAYER_RIVAL_RIGHT = "p2"
private const val PLAYER_PARTNER = "p3"
private const val PLAYER_RIVAL_LEFT = "p4"

// #27: ritmo de turno de la IA, UNIFICADO entre fases de apuesta y de
// declaración (PARES_CHECK/JUEGO_CHECK). Antes la apuesta usaba 1000ms y la
// declaración 750: la declaración se sentía más rápida y, al apretar el anuncio
// "Tengo/No tengo" contra su animación de entrada, era donde más parpadeaba al
// encadenar turnos. Un único tempo da ritmo constante y aire suficiente a cada
// anuncio (debe quedar >= la entrada+salida del ActionAnnouncement).
// 850 = compromiso de playtest: la declaración sube desde 750 (menos parpadeo)
// y la apuesta baja un pelín desde 1000 (1000 se sentía algo lento). Tunable.
private const val AI_TURN_PACING_MS = 850L

class GameViewModel constructor(
    internal val gameLogic: MusGameLogic,
    /**
     * Crea un [AILogic] para un perfil dado (#34). El ViewModel construye uno por
     * jugador de IA según su personalidad → los rivales dejan de ser clones.
     */
    private val aiLogicFactory: (AIProfile) -> AILogic,
    private val gameRepository: GameRepository
) : ViewModel() {

    private val TAG = "GameViewModelDebug"

    // Un AILogic por jugador de IA (keyed por playerId), reconstruido al arrancar
    // cada partida/escenario. En Fase A todos usan el perfil baseline; la
    // asignación de arquetipos por personaje llega en Fase B/C (ver profileFor).
    private var aiLogics: Map<String, AILogic> = emptyMap()

    private fun rebuildAiLogics(players: List<Player>, settings: GameSettings) {
        aiLogics = players.filter { it.isAi }
            .associate { player -> player.id to aiLogicFactory(profileFor(player, settings)) }
    }

    /**
     * Perfil de personalidad de un jugador de IA (#34, Fase C): mapea el asiento
     * (id fijo de defaultPlayers) al arquetipo elegido en GameSettings. El humano
     * y cualquier id desconocido caen a EQUILIBRADO. Mientras los presets de
     * AIProfile sigan == baseline, esto es no-op (todos los arquetipos resuelven
     * al mismo perfil); los deltas por arquetipo se calibran después.
     */
    private fun profileFor(player: Player, settings: GameSettings): AIProfile {
        val archetypeName = when (player.id) {
            PLAYER_PARTNER -> settings.partnerArchetype
            PLAYER_RIVAL_LEFT -> settings.rivalLeftArchetype
            PLAYER_RIVAL_RIGHT -> settings.rivalRightArchetype
            else -> return AIProfile.EQUILIBRADO
        }
        return AIArchetype.byName(archetypeName).profile
    }

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    private val _aiDebugLogs = MutableStateFlow<List<String>>(emptyList())
    val aiDebugLogs: StateFlow<List<String>> = _aiDebugLogs.asStateFlow()

    val humanPlayerId = "p1"

    private fun setGameState(newState: GameState) {
        _gameState.value = newState.copy(isPaused = _gameState.value.isPaused)
    }

    private suspend fun awaitNotPaused() {
        if (_gameState.value.isPaused) {
            _gameState.first { !it.isPaused }
        }
    }

    init {
        val savedData = gameRepository.loadState()
        if (savedData != null) {
            Log.d("GameViewModel", "Datos guardados encontrados. Reanudando con marcador.")
            val score = mapOf("teamA" to savedData.teamAScore, "teamB" to savedData.teamBScore)
            val chicos = mapOf("teamA" to savedData.chicosWonA, "teamB" to savedData.chicosWonB)
            // Pasamos el ID del último "mano" para que la rotación continúe correctamente
            startNewGame(score, savedData.lastManoPlayerId, chicos)
        } else {
            Log.d("GameViewModel", "No se encontraron datos. Empezando partida nueva.")
            // Empezamos una partida de cero, sin marcador ni mano previo
            startNewGame(null, null)
        }
    }

    // #34/#36: la mesa se construye desde la selección persistida en GameSettings
    // (humano + pareja + 2 rivales del CharacterRoster). Se conservan los ids de
    // asiento y equipos (p1+p3 = teamA, p2+p4 = teamB; pareja arriba, rivales a los
    // lados). Defaults del repo = la mesa clásica. profileFor sigue baseline (Fase C
    // conecta el arquetipo de cada personaje a su AILogic).
    private fun defaultPlayers(): List<Player> {
        val settings = gameRepository.loadSettings()
        val human = CharacterRoster.byId(settings.humanCharacterId)
        val partner = CharacterRoster.byId(settings.partnerCharacterId)
        val rivalLeft = CharacterRoster.byId(settings.rivalLeftCharacterId)
        val rivalRight = CharacterRoster.byId(settings.rivalRightCharacterId)
        return listOf(
            Player(
                id = PLAYER_HUMAN, name = settings.humanName,
                avatarResId = human.avatarResId, isAi = false, team = "teamA"
            ),
            Player(
                id = PLAYER_RIVAL_LEFT, name = rivalLeft.name,
                avatarResId = rivalLeft.avatarResId, isAi = true, team = "teamB"
            ),
            Player(
                id = PLAYER_PARTNER, name = partner.name,
                avatarResId = partner.avatarResId, isAi = true, team = "teamA"
            ),
            Player(
                id = PLAYER_RIVAL_RIGHT, name = rivalRight.name,
                avatarResId = rivalRight.avatarResId, isAi = true, team = "teamB"
            )
        )
    }

    /**
     * Arranca una partida de prueba con manos forzadas (panel de debug).
     *
     * Reparte las manos exactas del escenario y, salvo que pida arrancar en
     * MUS, emite un "No hay mus" del mano para aterrizar en GRANDE con las 16
     * cartas intactas (un solo NoMus cierra el Mus por reglas; no hay descarte
     * que altere las manos). A partir de ahí el motor es el normal.
     */
    fun startScenario(scenario: DebugScenario) {
        // #29: marcador y chicos iniciales del escenario (para testear finales
        // de chico/vaca sin llegar jugando). Las reglas (best-of) salen del repo.
        val score = mapOf("teamA" to scenario.teamAScore, "teamB" to scenario.teamBScore)
        val players = defaultPlayers().map {
            it.copy(hand = scenario.hands[it.id] ?: emptyList())
        }
        val settings = gameRepository.loadSettings()
        rebuildAiLogics(players, settings)
        val dealtCards = scenario.hands.values.flatten().toSet()
        val remainingDeck = gameLogic.createDeck().filter { it !in dealtCards }

        val musState = GameState(
            players = players,
            deck = remainingDeck,
            score = score,
            chicosWon = mapOf("teamA" to scenario.chicosWonA, "teamB" to scenario.chicosWonB),
            settings = settings,
            manoPlayerId = scenario.manoId,
            currentTurnPlayerId = scenario.manoId,
            gamePhase = GamePhase.MUS,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )

        if (scenario.startAtMus) {
            _gameState.value = onEnterMusPhase(musState)
        } else {
            // Un único "No hay mus" del mano cierra el Mus → GRANDE, sin tocar
            // las manos (handleNoMus no reparte).
            _gameState.value = gameLogic.processAction(
                musState, GameAction.NoMus, scenario.manoId
            )
        }
        handleAiTurn()
        triggerAiGestures()
    }


    fun onAction(action: GameAction, playerId: String) {
        when (action) {
            is GameAction.ToggleBetSelector -> {
                _gameState.value = _gameState.value.copy(isSelectingBet = true)
                return
            }
            is GameAction.CancelBetSelection -> {
                _gameState.value = _gameState.value.copy(isSelectingBet = false)
                return
            }
            else -> {
                if (_gameState.value.isSelectingBet) {
                    _gameState.value = _gameState.value.copy(isSelectingBet = false)
                }
            }
        }

        if (action is GameAction.TogglePauseMenu) {
            _gameState.value = _gameState.value.copy(isPaused = !_gameState.value.isPaused)
            return
        }

        if (_gameState.value.isPaused) {
            // Permitimos que NewGame funcione incluso en pausa
            if (action !is GameAction.NewGame) return
        }

        if (action is GameAction.ShowGesture) {
            // Mus corrido (#17): prohibidas las señas hasta que alguien corte.
            if (_gameState.value.musCorrido) return
            val player = _gameState.value.players.find { it.id == playerId } ?: return

            // Si el jugador está intentando desactivar su propia seña
            if (_gameState.value.activeGesture?.playerId == playerId) {
                _gameState.value = _gameState.value.copy(activeGesture = null)
                Log.d(TAG, "Player ${player.name} hid their gesture.")
                return
            }

            val gestureResId = determineGesture(player)
            if (gestureResId != null) {
                val gestureName = gestureIdToName(gestureResId)
                Log.d(TAG, "Player ${player.name} is showing gesture: '$gestureName'")

                val newGesture = ActiveGestureInfo(playerId, gestureResId)
                val updatedKnownGestures = _gameState.value.knownGestures + (playerId to newGesture)

                _gameState.value = _gameState.value.copy(
                    activeGesture = newGesture,
                    knownGestures = updatedKnownGestures
                )

                // --- TEMPORIZADOR PARA LA PARTE VISUAL ---
                // #20 (pieza C): el compañero del humano dura 1.5 s (legible);
                // rival/propia, flash corto. La IA la recuerda en
                // 'knownGestures' aparte (no depende de esta duración).
                viewModelScope.launch {
                    delay(gestureVisibleMs(playerId))
                    // Solo borra la seña si sigue siendo la misma que se activó
                    if (_gameState.value.activeGesture == newGesture) {
                        _gameState.value = _gameState.value.copy(activeGesture = null)
                    }
                }
            } else {
                Log.d(TAG, "Player ${player.name} tried to show a gesture, but has none.")
            }
            return
        }

        val currentState = _gameState.value
        when (action) {
            is GameAction.Continue -> {
                when {
                    // Vaca terminada: tras el resumen, a la pantalla de fin de
                    // partida (#26 - flujo secuencia).
                    currentState.winningTeam != null -> setGameState(
                        currentState.copy(
                            gamePhase = GamePhase.GAME_OVER,
                            availableActions = emptyList()
                        )
                    )
                    // #29: chico ganado pero la vaca sigue → chico nuevo desde
                    // 0-0, arrastrando los chicos ganados.
                    currentState.chicoJustWon != null -> startNewGame(
                        initialScore = null,
                        lastManoPlayerId = currentState.manoPlayerId,
                        chicosWon = currentState.chicosWon
                    )
                    // Siguiente ronda del mismo chico: se arrastra el marcador.
                    else -> startNewGame(
                        currentState.score,
                        currentState.manoPlayerId,
                        currentState.chicosWon
                    )
                }
                return
            }
            is GameAction.NewGame -> {
                gameRepository.deleteState() // Borra el estado guardado
                startNewGame(null, null)     // Inicia una partida desde cero
                return
            }
            else -> {
                if (currentState.gamePhase == GamePhase.ROUND_OVER || currentState.gamePhase == GamePhase.GAME_OVER) return
            }
        }

        val actionToProcess = if (currentState.currentTurnPlayerId == humanPlayerId) {
            when (currentState.gamePhase) {
                GamePhase.PARES_CHECK -> if (gameLogic.getHandPares(currentState.players.find { it.id == humanPlayerId }!!.hand).strength > 0) GameAction.Tengo else GameAction.NoTengo
                GamePhase.JUEGO_CHECK -> if (gameLogic.getHandJuegoValue(currentState.players.find { it.id == humanPlayerId }!!.hand) >= 31) GameAction.Tengo else GameAction.NoTengo
                else -> action
            }
        } else {
            action
        }

        val newState = gameLogic.processAction(currentState, actionToProcess, playerId)
        updateStateAndCheckAiTurn(newState)
    }

    private fun gestureIdToName(gestureResId: Int): String {
        return when (gestureResId) {
            R.drawable.reyes_2 -> "Dos Reyes"
            R.drawable.reyes_3 -> "Tres Reyes"
            R.drawable.ases_2 -> "Dos Ases"
            R.drawable.ases_3 -> "Tres Ases"
            R.drawable.sena_31 -> "31 de Juego"
            R.drawable.ciega -> "Ciega"
            R.drawable.duples_altos -> "Duples Altos"
            R.drawable.duples_bajos -> "Duples Bajos"
            else -> "Unknown Gesture"
        }
    }

    /**
     * #29 vacas: un equipo acaba de ganar un CHICO (por llegar a los tantos o
     * por órdago). Aplica la contabilidad de chicos y decide si la VACA ha
     * terminado. `baseState` ya trae score/desglose/ordagoInfo puestos; aquí
     * solo se tocan los flags de chico/vaca, la fase y la persistencia.
     *  - vaca terminada -> winningTeam (pantalla final).
     *  - vaca continúa  -> chicoJustWon (al Continuar arranca chico nuevo 0-0).
     * El camino del órdago usa GAME_OVER (overlay de órdago); el de los tantos
     * usa ROUND_OVER (muestra el desglose + banner del chico).
     */
    private fun applyChicoWin(baseState: GameState, chicoWinner: String, isOrdago: Boolean): GameState {
        val newChicos = baseState.chicosWon +
            (chicoWinner to (baseState.chicosWon[chicoWinner] ?: 0) + 1)
        val vacaOver = (newChicos[chicoWinner] ?: 0) >= baseState.settings.chicosToWinVaca

        if (vacaOver) {
            gameRepository.deleteState()
        } else {
            // El próximo chico arranca 0-0; si matan la app en el overlay, al
            // reanudar empezamos el chico nuevo limpio (con los chicos al día).
            gameRepository.saveState(
                SaveState(
                    teamAScore = 0,
                    teamBScore = 0,
                    lastManoPlayerId = baseState.manoPlayerId,
                    chicosWonA = newChicos["teamA"] ?: 0,
                    chicosWonB = newChicos["teamB"] ?: 0,
                    bestOfChicos = baseState.settings.bestOfChicos
                )
            )
        }

        return baseState.copy(
            chicosWon = newChicos,
            winningTeam = if (vacaOver) chicoWinner else null,
            chicoJustWon = if (vacaOver) null else chicoWinner,
            gamePhase = if (isOrdago) GamePhase.GAME_OVER else GamePhase.ROUND_OVER,
            availableActions = listOf(GameAction.Continue),
            revealAllHands = true
        )
    }

    /** El motor resuelve el órdago marcando GAME_OVER + winningTeam (= gana el
     *  chico). Aquí lo reinterpretamos en clave de vaca (#29). */
    private fun processOrdagoChicoEnd(ordagoState: GameState) {
        val chicoWinner = ordagoState.winningTeam ?: return
        setGameState(applyChicoWin(ordagoState, chicoWinner, isOrdago = true))
    }

    private fun processEndOfRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- Processing Scores ---")

        // El motor del juego nos da el estado con el desglose
        val stateWithBreakdown = gameLogic.scoreRound(roundEndState)
        val breakdown = stateWithBreakdown.scoreBreakdown ?: return

        // El desglose incluye los eventos instantáneos (la "no querida" ya se
        // sumó al marcador durante la ronda en handleNoQuiero). Para no contarlos
        // DOS VECES, el delta que aplicamos al marcador EXCLUYE esos eventos; el
        // desglose sí los sigue mostrando (atribución #24/#30).
        val instantA = roundEndState.scoreEvents.filter { it.teamId == "teamA" }.sumOf { it.detail.points }
        val instantB = roundEndState.scoreEvents.filter { it.teamId == "teamB" }.sumOf { it.detail.points }
        val pointsTeamA = breakdown.teamAScoreDetails.sumOf { it.points } - instantA
        val pointsTeamB = breakdown.teamBScoreDetails.sumOf { it.points } - instantB

        val currentScore = roundEndState.score
        val newScore = mapOf(
            "teamA" to (currentScore["teamA"]!! + pointsTeamA),
            "teamB" to (currentScore["teamB"]!! + pointsTeamB)
        )
        Log.d("MusVistoTest", "FINAL SCORE: $newScore")

        val scoreToWin = roundEndState.settings.pointsPerChico
        val chicoWinner = when {
            newScore["teamA"]!! >= scoreToWin -> "teamA"
            newScore["teamB"]!! >= scoreToWin -> "teamB"
            else -> null
        }

        val scoredState = stateWithBreakdown.copy(score = newScore)

        if (chicoWinner != null) {
            // Ronda decisiva del chico: mostramos el desglose (RoundEndOverlay) y,
            // al Continuar, o bien el siguiente chico o la pantalla de fin de
            // partida (#26). applyChicoWin se encarga de la persistencia.
            setGameState(applyChicoWin(scoredState, chicoWinner, isOrdago = false))
        } else {
            gameRepository.saveState(
                SaveState(
                    teamAScore = newScore["teamA"] ?: 0,
                    teamBScore = newScore["teamB"] ?: 0,
                    lastManoPlayerId = roundEndState.manoPlayerId,
                    chicosWonA = roundEndState.chicosWon["teamA"] ?: 0,
                    chicosWonB = roundEndState.chicosWon["teamB"] ?: 0,
                    bestOfChicos = roundEndState.settings.bestOfChicos
                )
            )
            setGameState(scoredState.copy(
                gamePhase = GamePhase.ROUND_OVER,
                availableActions = listOf(GameAction.Continue),
                revealAllHands = true
            ))
        }
    }

    fun onCardSelected(card: Card) {
        // --- LA CORRECCIÓN CLAVE ---
        // If it's not the human player's turn, do nothing.
        if (_gameState.value.currentTurnPlayerId != humanPlayerId) return

        val currentSelection = _gameState.value.selectedCardsForDiscard
        val newSelection = if (card in currentSelection) {
            currentSelection - card
        } else {
            currentSelection + card
        }
        _gameState.value = _gameState.value.copy(selectedCardsForDiscard = newSelection)
    }

    fun onToggleDebugMode() {
        _isDebugMode.value = !_isDebugMode.value
    }

    // Motor SERIALIZADO de avance de turno/declaración (#27). Un único Job
    // vivo a la vez: antes de lanzar el siguiente paso se cancela el anterior,
    // de modo que un avance de lance mata cualquier handleAiTurn rezagado
    // pendiente. Evita dos secuencias de declaración concurrentes escribiendo
    // los anuncios en paralelo (causa confirmada del parpadeo/solape).
    private var engineJob: Job? = null

    private fun handleAiTurn() {
        // Contexto esperado capturado ANTES del delay. Si al despertar la fase
        // o el turno han cambiado (otra coroutine ya avanzó el lance), este
        // turno quedó obsoleto → abortamos en vez de procesar a ciegas, que es
        // lo que disparaba la 2ª secuencia de declaración sobre un snapshot
        // viejo (anuncios que reviven y se pisan, #27).
        val expectedPhase = _gameState.value.gamePhase
        val expectedTurn = _gameState.value.currentTurnPlayerId
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            delay(AI_TURN_PACING_MS)
            awaitNotPaused()
            val currentState = _gameState.value
            if (currentState.gamePhase != expectedPhase ||
                currentState.currentTurnPlayerId != expectedTurn
            ) return@launch
            val currentPlayer =
                currentState.players.find { it.id == currentState.currentTurnPlayerId }

            if (currentPlayer != null && currentPlayer.isAi) {
                Log.d("MusVistoDebug", "AI's turn detected for: ${currentPlayer.name}")

                // Obtenemos la decisión completa de la IA (acción + cartas) con
                // el AILogic del perfil de ESTE jugador (#34). Fallback baseline
                // por si el mapa no estuviese poblado (no debería ocurrir).
                val ai = aiLogics[currentPlayer.id] ?: aiLogicFactory(AIProfile())
                val aiDecision = ai.makeDecision(currentState, currentPlayer)
                Log.d(
                    "MusVistoDebug",
                    "AI (${currentPlayer.name}) decided to: ${aiDecision.action.displayText}"
                )
                // En builds release `DebugFeatures.IS_ENABLED` es constante false y R8
                // elimina el bloque completo, así que el flow queda vacío.
                if (DebugFeatures.IS_ENABLED && aiDecision.debugLog.isNotEmpty()) {
                    _aiDebugLogs.value = (listOf(aiDecision.debugLog) + _aiDebugLogs.value).take(20)
                }

                // Si la acción es descartar, aplicamos la selección de cartas de la IA al estado
                if (aiDecision.action is GameAction.ConfirmDiscard) {
                    var cardsToDiscard = aiDecision.cardsToDiscard

                    // Red anti-cuelgue: decideDiscard ya garantiza un descarte
                    // no vacío, pero si alguna vez devolviese vacío el juego se
                    // colgaría. Defensa de profundidad barata; se mantiene.
                    if (cardsToDiscard.isEmpty() && currentPlayer.hand.isNotEmpty()) {
                        Log.e("GameViewModel", "AI LOGIC ERROR: AI decided to discard 0 cards. Forcing discard of 1 to prevent hang.")
                        cardsToDiscard = setOf(currentPlayer.hand.first())
                    }

                    _gameState.value =
                            // Usamos la lista de descarte (posiblemente corregida)
                        _gameState.value.copy(selectedCardsForDiscard = cardsToDiscard)
                }

                val stateBeforeAiAction = _gameState.value
                // Usamos la acción de la decisión para procesarla en la lógica del juego
                val newState = gameLogic.processAction(
                    stateBeforeAiAction,
                    aiDecision.action,
                    currentPlayer.id
                )

                updateStateAndCheckAiTurn(newState)
            }
        }
    }

    private fun updateStateAndCheckAiTurn(newState: GameState) {
        // Necesitamos saber si la fase ha cambiado para saber si debemos hacer la pausa de limpieza
        val phaseChanged = _gameState.value.gamePhase != newState.gamePhase
        // Actualizamos la UI inmediatamente con el nuevo estado
        setGameState(newState)

        // Si la ronda o la partida han terminado, la lógica no cambia
        if (newState.gamePhase == GamePhase.ROUND_OVER) {
            processEndOfRound(newState)
            return
        }
        if (newState.gamePhase == GamePhase.GAME_OVER) {
            // Único camino vivo motor->GAME_OVER = órdago ganado. En clave de
            // vaca (#29) eso gana el CHICO, no necesariamente la partida.
            processOrdagoChicoEnd(newState)
            return
        }

        // Lanzamos una corrutina para gestionar la secuencia de forma ordenada.
        // Parte del motor serializado (#27): cancela la anterior antes de
        // lanzar, para que el avance de lance mate cualquier paso rezagado.
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            // Frontera de lance. Durante este beat se mantienen visibles
            // TODAS las acciones del lance que acaba de cerrar (incl. la de
            // cierre) para que el jugador lea el lance resuelto sin perderse
            // qué hizo la IA. Pasado el beat, vaciamos `currentLanceActions`
            // de UNA SOLA VEZ y de forma SÍNCRONA, ANTES de que arranque la
            // primera acción del lance nuevo: fuente única, sin campo
            // transient, sin acción nueva concurrente ⇒ el toggle viejo↔nuevo
            // de #27 es imposible. Cada ActionAnnouncement ya superó su mínimo
            // visible durante el beat, así que su target→null se desvanece
            // limpio y sincronizado.
            if (phaseChanged) {
                delay(ANNOUNCEMENT_MIN_VISIBLE_MS)
                awaitNotPaused()
                _gameState.value = _gameState.value.copy(currentLanceActions = emptyMap())
            }

            // Re-entrada a MUS (tras descartar): popular pendingGestures y
            // disparar señas. Antes este 2º Mus no señalizaba — la corrutina
            // de gestures solo arrancaba en debugScenario/startNewRound — y
            // el humano jugaba sin info en los Mus subsiguientes.
            if (phaseChanged && _gameState.value.gamePhase == GamePhase.MUS) {
                _gameState.value = onEnterMusPhase(_gameState.value)
                triggerAiGestures()
            }

            // Continuamos con el lance actual.
            val currentState = _gameState.value
            if (currentState.gamePhase == GamePhase.PARES_CHECK || currentState.gamePhase == GamePhase.JUEGO_CHECK) {
                handleDeclarationSequence(currentState)
            } else {
                handleAiTurn()
            }
        }
    }
    private fun handleDeclarationSequence(currentState: GameState) {
        engineJob?.cancel()
        engineJob = viewModelScope.launch {
            var tempState = currentState
            val playersInOrder = gameLogic.getTurnOrderedPlayers(tempState.players, tempState.manoPlayerId)

            // Recorremos los jugadores uno por uno en orden de turno
            for (player in playersInOrder) {
                // Pausa por jugador, mismo tempo que las fases de apuesta (#27).
                delay(AI_TURN_PACING_MS)
                awaitNotPaused()

                // Determinamos si el jugador actual tiene jugada o no
                val hasPlay = if (tempState.gamePhase == GamePhase.PARES_CHECK) {
                    gameLogic.getHandPares(player.hand).strength > 0
                } else { // JUEGO_CHECK
                    gameLogic.getHandJuegoValue(player.hand) >= 31
                }
                val action = if (hasPlay) GameAction.Tengo else GameAction.NoTengo

                // Creamos el anuncio visual
                val actionInfo = LastActionInfo(player.id, action)
                tempState = tempState.copy(
                    lastAction = actionInfo,
                    currentLanceActions = tempState.currentLanceActions + (player.id to actionInfo)
                )
                setGameState(tempState)
            }

            // Una vez que todos han hablado, hacemos una última pausa (alineada
            // con el mínimo visible del composable) y avanzamos a la siguiente fase.
            delay(ANNOUNCEMENT_MIN_VISIBLE_MS)
            awaitNotPaused()
            // La función endLanceAndAdvance es la que contiene la lógica para saltar lances si es necesario
            val finalState = gameLogic.resolveDeclaration(tempState)
            updateStateAndCheckAiTurn(finalState)
        }
    }


    private fun startNewGame(
        initialScore: Map<String, Int>?,
        lastManoPlayerId: String?,
        chicosWon: Map<String, Int>? = null
    ) {
        val score = initialScore ?: mapOf("teamA" to 0, "teamB" to 0)
        // #29: las reglas vigentes mandan en la partida que arranca (la Fase 2
        // las edita desde el menú; en Fase 1 = best-of-3 por defecto).
        val settings = gameRepository.loadSettings()

        val players = _gameState.value.players.ifEmpty { defaultPlayers() }
        rebuildAiLogics(players, settings)

        val newManoId = if (lastManoPlayerId != null) {
            val lastManoIndex = players.indexOfFirst { it.id == lastManoPlayerId }
            val nextManoIndex = (lastManoIndex - 1 + players.size) % players.size
            players[nextManoIndex].id
        } else {
            players.first().id // Para la primera partida de todas
        }

        val initialDeck = gameLogic.createDeck()
        val shuffledDeck = gameLogic.shuffleDeck(initialDeck)

        val (updatedPlayers, remainingDeck) = gameLogic.dealCards(players, shuffledDeck, newManoId)

        // 1ª mano del juego (sin mano previa) → mus corrido (#17): el que corta
        // se vuelve mano; mientras, sin señas.
        val isFirstHand = lastManoPlayerId == null

        _gameState.value = onEnterMusPhase(
            GameState(
                players = updatedPlayers,
                deck = remainingDeck,
                score = score,
                chicosWon = chicosWon ?: mapOf("teamA" to 0, "teamB" to 0),
                settings = settings,
                manoPlayerId = newManoId,
                currentTurnPlayerId = newManoId,
                gamePhase = GamePhase.MUS,
                availableActions = listOf(GameAction.Mus, GameAction.NoMus),
                musCorrido = isFirstHand
            )
        )

        handleAiTurn()
        if (!isFirstHand) triggerAiGestures()
    }

    fun onBetAmountSelected(amount: Int) {
        val currentState = _gameState.value
        // Creamos la acción de envido con la cantidad seleccionada
        val envidoAction = GameAction.Envido(amount)
        // Procesamos la acción como si el jugador hubiera pulsado un botón con ese valor
        val newState = gameLogic.processAction(currentState, envidoAction, humanPlayerId)
        // Ocultamos el selector y actualizamos el estado del juego
        updateStateAndCheckAiTurn(newState.copy(isSelectingBet = false))
    }

    private fun determineGesture(player: Player): Int? {
        val hand = player.hand
        if (hand.isEmpty()) return null

        // --- Lógica de Conteo de Cartas Clave ---
        // Recuerda que los Treses cuentan como Reyes y los Doses como Ases para los pares.
        val reyesCount = hand.count { it.rank == Rank.REY || it.rank == Rank.TRES }
        val asesCount = hand.count { it.rank == Rank.AS || it.rank == Rank.DOS }

        val paresPlay = gameLogic.getHandPares(hand)
        val juegoValue = gameLogic.getHandJuegoValue(hand)

        // --- Comprobación de Señas por Orden de Prioridad ---


        // 1. Señas de Duples (dos pares)
        if (paresPlay is ParesPlay.Duples) {
            // #23: "duples altos" comunica EXACTAMENTE "tengo 2 reyes" (par
            // alto de Reyes). El resto de duples → "duples bajos". Así el
            // compañero puede leer la fuerza de Grande real de la seña.
            if (reyesCount >= 2) {
                return R.drawable.duples_altos // Seña: Duples Altos (2 reyes)
            } else {
                return R.drawable.duples_bajos // Seña: Duples Bajos
            }
        }

        // 2. Seña de 31 de Juego - La mejor jugada de Juego
        if (juegoValue == 31) {
            return R.drawable.sena_31 // Seña: 31
        }

        // 3. Señas de Medias (3 cartas iguales) - Son las más raras y específicas
        if (reyesCount >= 3) {
            return R.drawable.reyes_3 // Seña: Tres Reyes
        }
        if (asesCount >= 3) {
            return R.drawable.ases_3  // Seña: Tres Ases
        }

        // 4. Señas de Pares (dos cartas iguales)
        if (reyesCount == 2) {
            return R.drawable.reyes_2 // Seña: Dos Reyes
        }
        if (asesCount == 2) {
            return R.drawable.ases_2  // Seña: Dos Ases
        }

        // 5. Seña de Ciega (no llevar nada)
        // Se considera "ciega" si no se tiene ni Pares ni Juego (valor < 31)
        if (paresPlay is ParesPlay.NoPares && juegoValue < 31) {
            return R.drawable.ciega // Seña: Ciega
        }

        // Si no se cumple ninguna de las condiciones anteriores, no hay seña que pasar.
        return null
    }

    /**
     * ¿Tiene [player] una seña que pasar con su mano actual? (#38) Reutiliza
     * `determineGesture` para que la UI atenúe el botón de seña cuando el
     * jugador tiene jugada pero no es señalizable (p. ej. par de caballos,
     * juego ≠ 31): no hay seña que comunicar, el botón no debe parecer activo.
     */
    fun hasShowableGesture(player: Player): Boolean = determineGesture(player) != null

    /**
     * #20 (pieza C): cuánto dura visible en pantalla la seña de [signalerId].
     * Solo la del COMPAÑERO del humano es legible (1.5 s, para que decida el
     * corte que se le delega); la del rival y la propia, flash corto, para que
     * el humano no intercepte señas rivales gratis (la IA solo al 20%).
     */
    private fun gestureVisibleMs(signalerId: String): Long {
        val players = _gameState.value.players
        val humanTeam = players.find { it.id == humanPlayerId }?.team
        val signaler = players.find { it.id == signalerId }
        val isHumanPartner = humanTeam != null && signaler != null &&
            signaler.id != humanPlayerId && signaler.team == humanTeam
        return if (isHumanPartner) GESTURE_VISIBLE_PARTNER_MS else GESTURE_VISIBLE_OTHER_MS
    }

    /**
     * Asigna `pendingGestures` al entrar a la fase MUS. Para cada IA con
     * compañero: si `determineGesture` devuelve algo, tirada según
     * `isHumanPartner` (95%) o IA-IA (70%) para decidir si la pasa.
     * AILogic lee este map para gatear delegación/apoyo: si NO voy a
     * señalizar, juego mi mano normal (el partner —especialmente humano—
     * no tendrá info que use). Coherencia entre AILogic y
     * triggerAiGestures (corrutinas separadas) garantizada al leer ambas
     * del mismo state.
     */
    private fun onEnterMusPhase(state: GameState): GameState {
        // Mus corrido (#17): hasta el primer corte no hay señas → no se
        // planifican gestos. Sin `pendingGestures`, la delegación de corte #20
        // tampoco se dispara (su gate exige id en pendingGestures): cada uno
        // corta por su propia mano, como exige el modo.
        if (state.musCorrido) return state
        val humanTeam = state.players.find { it.id == humanPlayerId }?.team
        val pending = state.players.filter { ai ->
            ai.isAi && state.players.any { p -> p.id != ai.id && p.team == ai.team }
        }.mapNotNull { ai ->
            val gesture = determineGesture(ai) ?: return@mapNotNull null
            val isHumanPartner = humanTeam != null && ai.team == humanTeam
            val prob = if (isHumanPartner) PENDING_GESTURE_PROB_HUMAN_PARTNER
                       else PENDING_GESTURE_PROB_AI_PARTNER
            if (kotlin.random.Random.nextFloat() < prob) ai.id to gesture else null
        }.toMap()
        return state.copy(pendingGestures = pending)
    }

    private fun triggerAiGestures() {
        viewModelScope.launch {
            // Esperamos un poco para que no sea instantáneo
            delay(2000)
            awaitNotPaused()

            // #20 (pieza B): la IA emite seña tenga compañero IA o HUMANO. El
            // capitán humano necesita ver la seña para decidir el corte que el
            // primero le delega; antes solo se emitía IA->IA y el humano
            // jugaba a ciegas. La interceptación rival (opponentSignPerceived,
            // prob 0.20 fija) NO depende de esto, así que no añade exposición.
            // Lectura de `pendingGestures` (no tirada local): asegura que
            // AILogic.decideMusDelegation y esta corrutina ven la MISMA
            // decisión "voy a señalizar".
            val signalerIds = _gameState.value.pendingGestures.keys.toList()

            for (signalerId in signalerIds) {
                // La seña solo tiene sentido en MUS; tras los delay la fase o
                // la mano pueden haber cambiado, así que releemos el estado
                // fresco (no una copia stale): el humano decidirá su corte con
                // lo que vea, debe reflejar fase y mano reales.
                if (_gameState.value.gamePhase != GamePhase.MUS) return@launch
                // Solo si SIGUE en pendingGestures (no se canceló por refresh).
                if (signalerId !in _gameState.value.pendingGestures) continue
                onAction(GameAction.ShowGesture, signalerId)
                // Esperar a que ESTA seña agote su ventana visible para
                // que la siguiente no la pise. Es la duración propia del
                // emisor (compañero del humano = 1.5 s; rival = flash), así
                // una cadena de señas rivales no retrasa 1.5 s la del
                // compañero (era el "tarda un poco" del playtest).
                delay(gestureVisibleMs(signalerId))
                awaitNotPaused()
            }
        }
    }

}