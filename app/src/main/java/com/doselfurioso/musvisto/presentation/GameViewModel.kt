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
    private val gameLogic: MusGameLogic,
    private val aiLogic: AILogic
) : ViewModel() {

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    val humanPlayerId = "p1"

    init {
        startNewGame()
    }

    fun onAction(action: GameAction, playerId: String) {
        when (action) {
            is GameAction.Continue, is GameAction.NewGame -> {
                startNewGame()
            }
            is GameAction.ConfirmDiscard -> {
                // Handle discard and then check for AI turn
                val newState = gameLogic.processAction(_gameState.value, action, playerId)
                _gameState.value = newState
                handleAiTurn()
            }
            else -> {
                // Handle all other standard game actions
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
            delay(100) // Small delay to allow UI to update
            val currentState = _gameState.value
            val currentPlayerId = currentState.currentTurnPlayerId
            val currentPlayer = currentState.players.find { it.id == currentPlayerId }

            if (currentPlayer != null && currentPlayer.isAi) {
                val aiAction = aiLogic.makeDecision(currentState, currentPlayer)

                if (aiAction is GameAction.ConfirmDiscard) {
                    val cardToDiscard = currentPlayer.hand.firstOrNull()
                    if (cardToDiscard != null) {
                        _gameState.value = currentState.copy(selectedCardsForDiscard = setOf(cardToDiscard))
                    }
                }

                Log.d("MusVistoTest", "AI ${currentPlayer.name} performs action: ${aiAction.displayText}")

                delay(1500)
                val stateBeforeAiAction = _gameState.value
                val newState = gameLogic.processAction(stateBeforeAiAction, aiAction,
                    currentPlayerId.toString()
                )

                if (newState.gamePhase == GamePhase.ROUND_OVER) {
                    processEndOfRound(newState)
                } else {
                    _gameState.value = newState
                    handleAiTurn()
                }
            }
        }
    }

    private fun startNewGame() {
        val players = listOf(
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamA"),
            Player(id = "p4", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true, team = "teamB")
        )

        val initialDeck = gameLogic.createDeck()
        val shuffledDeck = gameLogic.shuffleDeck(initialDeck)
        val (updatedPlayers, remainingDeck) = gameLogic.dealCards(players, shuffledDeck)

        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            gamePhase = GamePhase.MUS_DECISION,
            currentTurnPlayerId = players.first().id,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }
}