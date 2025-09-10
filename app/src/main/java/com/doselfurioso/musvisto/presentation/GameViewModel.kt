package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AILogic
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    internal val gameLogic: MusGameLogic,
    private val aiLogic: AILogic,
    private val gameRepository: GameRepository
) : ViewModel() {

    private val manualDealingEnabled = false

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    val humanPlayerId = "p1"

    init {
        val savedGame = gameRepository.loadGameState()
        if (savedGame != null) {
            Log.d("GameViewModel", "Partida guardada encontrada. Cargando...")
            _gameState.value = recalculateTransientState(savedGame)
            handleAiTurn()
        } else {
            Log.d("GameViewModel", "No se encontró partida guardada. Empezando una nueva.")
            startNewGame(null)
        }
    }
    private fun recalculateTransientState(loadedState: GameState): GameState {
        val actions = when (loadedState.gamePhase) {
            GamePhase.MUS -> listOf(GameAction.Mus, GameAction.NoMus)
            GamePhase.DISCARD -> listOf(GameAction.ConfirmDiscard)
            GamePhase.ROUND_OVER, GamePhase.GAME_OVER -> emptyList()
            else -> listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        }
        return loadedState.copy(availableActions = actions)
    }


    private fun dealManualHands(players: List<Player>, deck: List<Card>): Pair<List<Player>, List<Card>> {
        // Define aquí las manos que quieres probar
        val manualHands = mapOf(
            "p1" to listOf( // Mano del Jugador Humano
                Card(Suit.OROS, Rank.REY),
                Card(Suit.OROS, Rank.CABALLO),
                Card(Suit.OROS, Rank.SOTA),
                Card(Suit.OROS, Rank.CABALLO)
            ),
            "p2" to listOf( // Mano del Rival Izquierdo
                Card(Suit.COPAS, Rank.REY),
                Card(Suit.COPAS, Rank.CUATRO),
                Card(Suit.COPAS, Rank.CABALLO),
                Card(Suit.COPAS, Rank.AS)
            ),
            "p3" to listOf( // Mano del Compañero
                Card(Suit.OROS, Rank.REY),
                Card(Suit.OROS, Rank.CABALLO),
                Card(Suit.OROS, Rank.SOTA),
                Card(Suit.OROS, Rank.CABALLO)
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

        val currentState = _gameState.value
        when (action) {
            is GameAction.Continue -> {
                startNewGame(_gameState.value)
                return
            }
            is GameAction.NewGame -> {
                gameRepository.deleteGameState()
                startNewGame(null)
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

        val scoreToWin = 40
        val winner = if (newScore["teamA"]!! >= scoreToWin) "teamA" else if (newScore["teamB"]!! >= scoreToWin) "teamB" else null

        if (winner != null) {
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
        gameRepository.saveGameState(newState)
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


    private fun startNewGame(previousState: GameState?) {
        val score = previousState?.score ?: mapOf("teamA" to 0, "teamB" to 0)

        // El orden de los jugadores en la lista es FIJO y representa su asiento en la mesa
        val players = previousState?.players?.map { it.copy(hand = emptyList()) } ?: listOf(
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
            Player(id = "p4", name = "Luis", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamA"),
            Player(id = "p2", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true, team = "teamB")
        )

        // Se calcula la nueva "mano" rotando desde la anterior
        val newManoId = previousState?.let {
            val lastManoIndex = it.players.indexOfFirst { p -> p.id == it.manoPlayerId }.takeIf { it != -1 } ?: -1
            val nextManoIndex = (lastManoIndex - 1 + players.size) % players.size
            players[nextManoIndex].id
        } ?: players.first().id // Para la primera partida, la mano es p1


        val initialDeck = gameLogic.createDeck()
        val shuffledDeck = gameLogic.shuffleDeck(initialDeck)

        // Se reparten las cartas
        val (updatedPlayers, remainingDeck) = if (manualDealingEnabled) {
            dealManualHands(players, shuffledDeck)
        } else {
            gameLogic.dealCards(players, shuffledDeck, newManoId)
        }
        // Se crea el nuevo estado del juego
        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            score = score,
            manoPlayerId = newManoId,
            currentTurnPlayerId = newManoId, // <-- La ronda EMPIEZA con la mano
            gamePhase = GamePhase.MUS,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus),
            lastAction = null,
            playersWhoPassed = emptySet(),
            currentBet = null,
            agreedBets = emptyMap(),
            isPuntoPhase = false,
            discardCounts = emptyMap(),
            selectedCardsForDiscard = emptySet(),
            revealAllHands = false,
            roundHistory = emptyList(),
            scoreEvents = emptyList(),
            actionLog = emptyList()
        )
        // Inmediatamente después de establecer el estado, comprobamos si le toca a una IA
        handleAiTurn()
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

}