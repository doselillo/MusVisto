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

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    val humanPlayerId = "p1"

    init {
        startNewGame(null)
    }

    fun onAction(action: GameAction, playerId: String) {
        // --- LÓGICA DE FLUJO CORREGIDA ---
        when (action) {
            is GameAction.Continue -> {
                startNewGame(_gameState.value) // Continúa la partida, mantiene el marcador
                return
            }
            is GameAction.NewGame -> {
                startNewGame(null) // Reinicia la partida, resetea el marcador
                return
            }
            else -> {
                // Procesa una acción de juego normal
                val currentState = _gameState.value
                if (currentState.gamePhase == GamePhase.ROUND_OVER || currentState.gamePhase == GamePhase.GAME_OVER) return

                val newState = gameLogic.processAction(currentState, action, playerId)

                if (newState.gamePhase == GamePhase.ROUND_OVER) {
                    processEndOfRound(newState)
                } else {
                    _gameState.value = newState
                    handleAiTurn()
                }
            }
        }
    }

    private fun processEndOfRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- Processing Scores ---")

        val manoOfFinishedRound = roundEndState.manoPlayerId
        Log.d("MusVistoDebug", "processEndOfRound: The mano from the round that just ended was: $manoOfFinishedRound")

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
                availableActions = listOf(GameAction.NewGame)
            )
        } else {
            _gameState.value = scoredState.copy(
                gamePhase = GamePhase.ROUND_OVER,
                availableActions = listOf(GameAction.Continue)
            )
        }
        Log.d("MusVistoDebug", "processEndOfRound: The final state before 'Continue' now has mano: ${_gameState.value.manoPlayerId}")

    }

    fun onCardSelected(card: Card) {
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
            delay(100)
            val currentState = _gameState.value
            val currentPlayer = currentState.players.find { it.id == currentState.currentTurnPlayerId }

            if (currentPlayer != null && currentPlayer.isAi) {
                Log.d("MusVistoDebug", "AI's turn detected for: ${currentPlayer.name}")

                val aiAction = aiLogic.makeDecision(currentState, currentPlayer)
                Log.d("MusVistoDebug", "AI (${currentPlayer.name}) decided to: ${aiAction.displayText}")

                if (aiAction is GameAction.ConfirmDiscard) {
                    val cardToDiscard = currentPlayer.hand.firstOrNull()
                    if (cardToDiscard != null) {
                        _gameState.value = currentState.copy(selectedCardsForDiscard = setOf(cardToDiscard))
                    }
                }

                delay(500)

                val stateBeforeAiAction = _gameState.value
                val newState = gameLogic.processAction(stateBeforeAiAction, aiAction, currentPlayer.id)

                _gameState.value = newState

                if (newState.gamePhase == GamePhase.ROUND_OVER) {
                    processEndOfRound(newState)
                    return@launch
                }
                if (newState.gamePhase == GamePhase.GAME_OVER) {
                    return@launch
                }

                val nextPlayerIsAi = newState.players.find { it.id == newState.currentTurnPlayerId }?.isAi ?: false
                if (nextPlayerIsAi) {
                    Log.d("MusVistoDebug", "Next player is also an AI, running handleAiTurn again...")
                    handleAiTurn()
                }
            }
        }
    }

    private fun startNewGame(previousState: GameState?) {
        val score = previousState?.score ?: mapOf("teamA" to 0, "teamB" to 0)

        // El orden de los jugadores en la lista es FIJO y representa su asiento en la mesa
        val players = previousState?.players?.map { it.copy(hand = emptyList()) } ?: listOf(
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamA"),
            Player(id = "p4", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true, team = "teamB")
        )

        // Se calcula la nueva "mano" rotando desde la anterior
        val newManoId = previousState?.let {
            val lastManoIndex = it.players.indexOfFirst { p -> p.id == it.manoPlayerId }.takeIf { it != -1 } ?: -1
            val nextManoIndex = (lastManoIndex + 1) % players.size
            players[nextManoIndex].id
        } ?: players.first().id // Para la primera partida, la mano es p1

        Log.d("MusVistoDebug", "New round starting. Mano is: $newManoId")

        val initialDeck = gameLogic.createDeck()
        val shuffledDeck = gameLogic.shuffleDeck(initialDeck)

        // Se reparten las cartas
        val (updatedPlayers, remainingDeck) = gameLogic.dealCards(players, shuffledDeck, newManoId)

        // Se crea el nuevo estado del juego
        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            score = score,
            manoPlayerId = newManoId,
            currentTurnPlayerId = newManoId, // <-- La ronda EMPIEZA con la mano
            gamePhase = GamePhase.MUS_DECISION,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus),
            lastAction = null,
            playersWhoPassed = emptySet(),
            currentBet = null,
            agreedBets = emptyMap(),
            isPuntoPhase = false,
            discardCounts = emptyMap(),
            selectedCardsForDiscard = emptySet()
        )
        // Inmediatamente después de establecer el estado, comprobamos si le toca a una IA
        handleAiTurn()
    }
}