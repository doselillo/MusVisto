package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AILogic
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class GameViewModel constructor(
    internal val gameLogic: MusGameLogic,
    private val aiLogic: AILogic,
    private val gameRepository: GameRepository
) : ViewModel() {

    private val manualDealingEnabled = true

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    val humanPlayerId = "p1"

    init {
        val savedData = gameRepository.loadState()
        if (savedData != null) {
            Log.d("GameViewModel", "Datos guardados encontrados. Reanudando con marcador.")
            val score = mapOf("teamA" to savedData.teamAScore, "teamB" to savedData.teamBScore)
            // Pasamos el ID del último "mano" para que la rotación continúe correctamente
            startNewGame(score, savedData.lastManoPlayerId)
        } else {
            Log.d("GameViewModel", "No se encontraron datos. Empezando partida nueva.")
            // Empezamos una partida de cero, sin marcador ni mano previo
            startNewGame(null, null)
        }
    }

    private fun dealManualHands(players: List<Player>, deck: List<Card>): Pair<List<Player>, List<Card>> {
        // Define aquí las manos que quieres probar
        val manualHands = mapOf(
            "p1" to listOf( // Mano del Jugador Humano
                Card(Suit.OROS, Rank.REY),
                Card(Suit.OROS, Rank.CABALLO),
                Card(Suit.OROS, Rank.CABALLO),
                Card(Suit.OROS, Rank.AS)
            ),
            "p2" to listOf( // Mano del Rival Izquierdo
                Card(Suit.COPAS, Rank.REY),
                Card(Suit.COPAS, Rank.REY),
                Card(Suit.COPAS, Rank.CINCO),
                Card(Suit.COPAS, Rank.CINCO)
            ),
            "p3" to listOf( // Mano del Compañero
                Card(Suit.OROS, Rank.REY),
                Card(Suit.OROS, Rank.CABALLO),
                Card(Suit.OROS, Rank.SOTA),
                Card(Suit.OROS, Rank.CUATRO)
            ),
            "p4" to listOf( // Mano del Rival Derecho
                Card(Suit.BASTOS, Rank.REY),
                Card(Suit.BASTOS, Rank.CABALLO),
                Card(Suit.BASTOS, Rank.CUATRO),
                Card(Suit.BASTOS, Rank.AS)
            )
        )

        val updatedPlayers = players.map { it.copy(hand = manualHands[it.id] ?: emptyList()) }

        // Eliminamos las cartas repartidas del mazo para que no haya duplicados
        val dealtCards = manualHands.values.flatten().toSet()
        val remainingDeck = deck.filter { it !in dealtCards }

        return Pair(updatedPlayers, remainingDeck)
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
            val player = _gameState.value.players.find { it.id == playerId } ?: return
            val gestureResId = determineGesture(player)

            // Si el jugador ya tiene una seña activa, la quitamos
            if (_gameState.value.activeGesture?.playerId == playerId) {
                _gameState.value = _gameState.value.copy(activeGesture = null)
                return
            }

            if (gestureResId != null) {
                viewModelScope.launch {
                    _gameState.value = _gameState.value.copy(activeGesture = ActiveGestureInfo(playerId, gestureResId))
                    delay(250) // Duración de la seña
                    // Solo quitamos la seña si sigue siendo la misma que activamos
                    if (_gameState.value.activeGesture?.playerId == playerId) {
                        _gameState.value = _gameState.value.copy(activeGesture = null)
                    }
                }
            }
            return // Importante para detener la ejecución aquí
        }

        val currentState = _gameState.value
        when (action) {
            is GameAction.Continue -> {
                startNewGame(currentState.score, currentState.manoPlayerId)
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



    private fun processEndOfRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- Processing Scores ---")

        // El motor del juego nos da el estado con el desglose
        val stateWithBreakdown = gameLogic.scoreRound(roundEndState)
        val breakdown = stateWithBreakdown.scoreBreakdown ?: return

        // Calculamos los nuevos totales a partir del desglose
        val pointsTeamA = breakdown.teamAScoreDetails.sumOf { it.points }
        val pointsTeamB = breakdown.teamBScoreDetails.sumOf { it.points }

        val currentScore = roundEndState.score
        val newScore = mapOf(
            "teamA" to (currentScore["teamA"]!! + pointsTeamA),
            "teamB" to (currentScore["teamB"]!! + pointsTeamB)
        )
        Log.d("MusVistoTest", "FINAL SCORE: $newScore")

        val stateToSave = SaveState(
            teamAScore = newScore["teamA"] ?: 0,
            teamBScore = newScore["teamB"] ?: 0,
            lastManoPlayerId = roundEndState.manoPlayerId
        )

        gameRepository.saveState(stateToSave)

        val scoreToWin = 40
        val winner = if (newScore["teamA"]!! >= scoreToWin) "teamA" else if (newScore["teamB"]!! >= scoreToWin) "teamB" else null

        if (winner != null) {
            gameRepository.deleteState()
            _gameState.value = stateWithBreakdown.copy(
                score = newScore,
                gamePhase = GamePhase.GAME_OVER,
                winningTeam = winner,
                availableActions = emptyList(),
                revealAllHands = true
            )
        } else {
            _gameState.value = stateWithBreakdown.copy(
                score = newScore,
                gamePhase = GamePhase.ROUND_OVER,
                availableActions = listOf(GameAction.Continue),
                revealAllHands = true
            )
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

    private fun handleAiTurn() {
        viewModelScope.launch {
            delay(1000)
            val currentState = _gameState.value
            val currentPlayer =
                currentState.players.find { it.id == currentState.currentTurnPlayerId }

            if (currentPlayer != null && currentPlayer.isAi) {
                Log.d("MusVistoDebug", "AI's turn detected for: ${currentPlayer.name}")

                // Obtenemos la decisión completa de la IA (acción + cartas)
                val aiDecision = aiLogic.makeDecision(currentState, currentPlayer)
                Log.d(
                    "MusVistoDebug",
                    "AI (${currentPlayer.name}) decided to: ${aiDecision.action.displayText}"
                )

                // Si la acción es descartar, aplicamos la selección de cartas de la IA al estado
                if (aiDecision.action is GameAction.ConfirmDiscard) {
                    var cardsToDiscard = aiDecision.cardsToDiscard

                    // --- INICIO DE LA CORRECCIÓN ---
                    // AÑADIMOS UNA RED DE SEGURIDAD:
                    // Si la IA, por error, devuelve una lista de descarte vacía,
                    // forzamos que descarte al menos una carta para evitar que el juego se cuelgue.
                    if (cardsToDiscard.isEmpty() && currentPlayer.hand.isNotEmpty()) {
                        Log.e("GameViewModel", "AI LOGIC ERROR: AI decided to discard 0 cards. Forcing discard of 1 to prevent hang.")
                        // Como fallback, descartamos la primera carta de su mano.
                        cardsToDiscard = setOf(currentPlayer.hand.first())
                    }
                    // --- FIN DE LA CORRECCIÓN ---

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
        _gameState.value = newState

        // Si la ronda o la partida han terminado, la lógica no cambia
        if (newState.gamePhase == GamePhase.ROUND_OVER) {
            processEndOfRound(newState)
            return
        }
        if (newState.gamePhase == GamePhase.GAME_OVER) {
            return
        }

        // Lanzamos una corrutina para gestionar la secuencia de forma ordenada
        viewModelScope.launch {
            // SI LA FASE HA CAMBIADO, significa que un lance acaba de terminar.
            // Ahora, nos detenemos y esperamos a que su último anuncio desaparezca.
            if (phaseChanged) {
                // La información del último anuncio está en `newState.transientAction`
                // Le damos 1.5 segundos para que se muestre y su animación de salida termine.
                delay(1000)

                // Pasado el tiempo, nos aseguramos de que el estado esté limpio
                // antes de continuar.
                if (_gameState.value.transientAction == newState.transientAction) {
                    _gameState.value = _gameState.value.copy(
                        transientAction = null,
                        currentLanceActions = emptyMap()
                    )
                }
            }

            // AHORA que la pantalla está limpia, continuamos con el lance actual.
            val currentState = _gameState.value // Volvemos a leer el estado por si ha cambiado
            if (currentState.gamePhase == GamePhase.PARES_CHECK || currentState.gamePhase == GamePhase.JUEGO_CHECK) {
                handleDeclarationSequence(currentState)
            } else {
                handleAiTurn()
            }
        }
    }
    private fun handleDeclarationSequence(currentState: GameState) {
        viewModelScope.launch {
            var tempState = currentState
            val playersInOrder = gameLogic.getTurnOrderedPlayers(tempState.players, tempState.manoPlayerId)

            // Recorremos los jugadores uno por uno en orden de turno
            for (player in playersInOrder) {
                // Hacemos una pausa para que el efecto sea visible
                delay(750)

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
                _gameState.value = tempState
            }

            // Una vez que todos han hablado, hacemos una última pausa y avanzamos a la siguiente fase
            delay(1000)
            // La función endLanceAndAdvance es la que contiene la lógica para saltar lances si es necesario
            val finalState = gameLogic.resolveDeclaration(tempState)
            updateStateAndCheckAiTurn(finalState)
        }
    }


    private fun startNewGame(initialScore: Map<String, Int>?, lastManoPlayerId: String?) {
        val score = initialScore ?: mapOf("teamA" to 0, "teamB" to 0)

        val players = _gameState.value.players.ifEmpty {
            listOf(
                Player(id = "p1", name = "Tú", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
                Player(id = "p4", name = "Rival Der.", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamB"),
                Player(id = "p3", name = "Compañero", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamA"),
                Player(id = "p2", name = "Rival Izq.", avatarResId = R.drawable.avatar_granada, isAi = true, team = "teamB")
            )
        }

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

        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            score = score,
            manoPlayerId = newManoId,
            currentTurnPlayerId = newManoId,
            gamePhase = GamePhase.MUS,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )

        handleAiTurn()
        triggerAiGestures()
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
            // Para saber si son altos o bajos, miramos el par más bajo.
            // Si el par bajo es Sota o superior, son Duples Altos.
            if (paresPlay.lowPair.value >= Rank.SOTA.value) {
                return R.drawable.duples_altos // Seña: Duples Altos
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

    private fun triggerAiGestures() {
        viewModelScope.launch {
            val currentState = _gameState.value

            // --- INICIO DE LA CORRECCIÓN ---
            // Ahora seleccionamos a las IAs cuyo compañero TAMBIÉN es una IA.
            val aiPlayersWithAiPartners = currentState.players.filter { aiPlayer ->
                aiPlayer.isAi && currentState.players.any { partner ->
                    partner.id != aiPlayer.id && partner.team == aiPlayer.team && partner.isAi
                }
            }
            // --- FIN DE LA CORRECCIÓN ---

            // Esperamos un poco para que no sea instantáneo
            delay(2000)

            // El resto de la función itera sobre la nueva lista
            for (aiPlayer in aiPlayersWithAiPartners) {
                // 70% de probabilidad de que la IA decida pasar una seña
                if (kotlin.random.Random.nextFloat() < 0.70f) {
                    val gestureResId = determineGesture(aiPlayer)
                    if (gestureResId != null) {
                        onAction(GameAction.ShowGesture, aiPlayer.id)
                        // Pequeña pausa por si varias IAs quisieran pasar seña
                        delay(500)
                    }
                }
            }
        }
    }

}