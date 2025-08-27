package com.doselfurioso.musvisto.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AILogic
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
    private val aiLogic: AILogic
) : ViewModel() {

    private val manualDealingEnabled = true

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    val humanPlayerId = "p1"

    init {
        startNewGame(null)
    }

    private fun dealManualHands(players: List<Player>, deck: List<Card>): Pair<List<Player>, List<Card>> {
        // Define aquí las manos que quieres probar
        val manualHands = mapOf(
            "p1" to listOf( // Mano del Jugador Humano
                Card(Suit.OROS, Rank.REY),
                Card(Suit.ESPADAS, Rank.REY),
                Card(Suit.BASTOS, Rank.REY),
                Card(Suit.COPAS, Rank.REY)
            ),
            "p2" to listOf( // Mano del Rival Izquierdo
                Card(Suit.OROS, Rank.REY),
                Card(Suit.ESPADAS, Rank.SOTA),
                Card(Suit.BASTOS, Rank.CUATRO),
                Card(Suit.COPAS, Rank.AS)
            ),
            "p3" to listOf( // Mano del Compañero
                Card(Suit.OROS, Rank.REY),
                Card(Suit.ESPADAS, Rank.SOTA),
                Card(Suit.BASTOS, Rank.CUATRO),
                Card(Suit.COPAS, Rank.AS)
            ),
            "p4" to listOf( // Mano del Rival Derecho
                Card(Suit.OROS, Rank.REY),
                Card(Suit.ESPADAS, Rank.SOTA),
                Card(Suit.BASTOS, Rank.CUATRO),
                Card(Suit.COPAS, Rank.AS)
            )
        )

        val updatedPlayers = players.map { it.copy(hand = manualHands[it.id] ?: emptyList()) }

        // Eliminamos las cartas repartidas del mazo para que no haya duplicados
        val dealtCards = manualHands.values.flatten().toSet()
        val remainingDeck = deck.filter { it !in dealtCards }

        return Pair(updatedPlayers, remainingDeck)
    }

    fun onAction(action: GameAction, playerId: String) {
        val currentState = _gameState.value
        when (action) {
            is GameAction.Continue -> {
                startNewGame(_gameState.value)
                return
            }
            is GameAction.NewGame -> {
                startNewGame(null)
                return
            }
            else -> {
                if (currentState.gamePhase == GamePhase.ROUND_OVER || currentState.gamePhase == GamePhase.GAME_OVER) return
            }
        }

        // Obtenemos el nuevo estado desde la lógica pura
        val newState = gameLogic.processAction(currentState, action, playerId)

        // El ViewModel ahora gestiona las transiciones
        updateStateAndCheckAiTurn(newState)
    }

    private fun handleGameEvent(event: GameEvent?) {
        if (event == null) return

        // Launch a separate coroutine to manage the event's lifecycle
        viewModelScope.launch {
            // The UI will show the event because it's in the state.
            // We wait for its duration.
            delay(3000)
            // After the delay, we clear the event from the state.
            _gameState.value = _gameState.value.copy(event = null)
        }
    }

    private fun processEndOfRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- Processing Scores ---")

        val manoOfFinishedRound = roundEndState.manoPlayerId

        val scoredState = gameLogic.scoreRound(roundEndState)
        Log.d("MusVistoTest", "FINAL SCORE: ${scoredState.score}")

        val scoreToWin = 40
        val teamAScore = scoredState.score["teamA"] ?: 0
        val teamBScore = scoredState.score["teamB"] ?: 0
        val winner = if (teamAScore >= scoreToWin) "teamA" else if (teamBScore >= scoreToWin) "teamB" else null

        if (winner != null) {
            _gameState.value = scoredState.copy(
                gamePhase = GamePhase.GAME_OVER,
                winningTeam = winner,
                availableActions = listOf(GameAction.NewGame),
                revealAllHands = true
            )
        } else {
            _gameState.value = scoredState.copy(
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
            delay(500)
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
                    _gameState.value =
                        _gameState.value.copy(selectedCardsForDiscard = aiDecision.cardsToDiscard)
                }

                delay(1000)

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
        // El ViewModel ahora es el que gestiona la lógica de la transición.
        handleTransientAction(newState.transientAction)

        // El resto de la lógica se mantiene.
        handleGameEvent(newState.event)
        _gameState.value = newState

        if (newState.gamePhase == GamePhase.ROUND_OVER) {
            processEndOfRound(newState)
        } else {
            // Solo llamamos a la IA si no estamos en una transición de limpieza.
            if (newState.transientAction == null) {
                handleAiTurn()
            }
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
            roundHistory = emptyList(), // <-- AÑADE ESTA LÍNEA
            actionLog = emptyList()
        )
        // Inmediatamente después de establecer el estado, comprobamos si le toca a una IA
        handleAiTurn()
    }

    private fun handleTransientAction(transientAction: LastActionInfo?) {
        if (transientAction == null) return

        viewModelScope.launch {
            // Esperamos un momento para que el jugador vea el estado final del lance.
            delay(1000)

            // Pasado el tiempo, si el estado no ha cambiado, limpiamos TODO.
            if (_gameState.value.transientAction == transientAction) {
                _gameState.value = _gameState.value.copy(
                    transientAction = null,
                    currentLanceActions = emptyMap() // <-- Limpiamos todos los anuncios a la vez
                )
            }
        }
    }
}