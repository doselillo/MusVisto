package com.doselfurioso.musvisto.presentation


import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// This annotation tells Hilt that this is a ViewModel and it can be injected.
@HiltViewModel
class GameViewModel @Inject constructor(
    private val logic: MusGameLogic
) : ViewModel() {

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    // We removed the hardcoded humanPlayerId
    val humanPlayerId = "p1"

    init {
        startNewGame()
    }

    // MODIFIED: This function now takes the playerId as a parameter
    fun onAction(action: GameAction, playerId: String) {
        val currentState = _gameState.value
        val player = currentState.players.find { it.id == playerId }

        // Safety check: only process actions from human players
        if (player == null || player.isAi) {
            return
        }

        Log.d("MusVistoTest", "Player $playerId performed action: ${action.displayText}")

        val newState = logic.processAction(currentState, action, playerId)
        _gameState.value = newState

        // After our action, check if it's an AI's turn
        if (newState.gamePhase == GamePhase.SCORING) {
            finalizeRound(newState)
        } else {
            // If not ended, check if it's an AI's turn
            handleAiTurn()
        }
        if (action is GameAction.NewGame) {
            startNewGame()
            return
        }
    }

    private fun finalizeRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- SCORING ---")

        val scoredState = logic.scoreRound(roundEndState)
        _gameState.value = scoredState

        Log.d("MusVistoTest", "FINAL SCORE: ${scoredState.score}")

        // --- NEW: VICTORY CHECK ---
        val scoreToWin = 4 // Standard Mus is 40 points
        val teamAScore = scoredState.score["teamA"] ?: 0
        val teamBScore = scoredState.score["teamB"] ?: 0

        val winner = when {
            teamAScore >= scoreToWin -> "teamA"
            teamBScore >= scoreToWin -> "teamB"
            else -> null
        }

        if (winner != null) {
            // --- GAME OVER ---
            Log.d("MusVistoTest", "GAME OVER! Winner is $winner")
            _gameState.value = scoredState.copy(
                gamePhase = GamePhase.GAME_OVER,
                winningTeam = winner,
                availableActions = listOf(GameAction.NewGame) // A new action
            )
        } else {
            // --- CONTINUE TO NEXT ROUND ---
            viewModelScope.launch {
                delay(5000) // Wait 5 seconds to show the results
                Log.d("MusVistoTest", "--- STARTING NEW ROUND ---")
                startNewGame()
            }
        }
    }

    private fun handleAiTurn() {
        val currentState = _gameState.value
        val currentPlayerId = currentState.currentTurnPlayerId
        val currentPlayer = currentState.players.find { it.id == currentPlayerId }

        // First, check if it's an AI's turn
        if (currentPlayer != null && currentPlayer.isAi) {

            // THE KEY CHANGE IS HERE: The AI now chooses the correct action based on the phase
            val aiAction = when (currentState.gamePhase) {
                GamePhase.MUS_DECISION -> GameAction.Mus
                GamePhase.DISCARD -> {
                    // For now, the AI will always discard one card (we'll make this smarter later)
                    // We need to set the selected card in the state before calling the action
                    val cardToDiscard = currentPlayer.hand.first()
                    _gameState.value = currentState.copy(selectedCardsForDiscard = setOf(cardToDiscard))
                    GameAction.ConfirmDiscard
                }
                else -> GameAction.Paso // For Grande, Chica, etc., the AI will just pass for now
            }

            Log.d("MusVistoTest", "AI ${currentPlayer.name} performs action: ${aiAction.displayText}")

            viewModelScope.launch {
                delay(1500) // 1.5 second delay

                // We need to get the most current state, as it might have been modified
                val stateBeforeAiAction = _gameState.value
                val newState = logic.processAction(stateBeforeAiAction, aiAction,
                    currentPlayerId.toString()
                )
                _gameState.value = newState

                // Check again in case the next player is also an AI
                if (newState.gamePhase != GamePhase.SCORING) {
                    handleAiTurn()
                }
            }
        }
    }

    private fun startNewGame() {
        val players = listOf(
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamA"), // Let's make player 3 human for now
            Player(id = "p4", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true,  team = "teamB")
        )
        val deck = logic.shuffleDeck(logic.createDeck())
        val (updatedPlayers, remainingDeck) = logic.dealCards(players, deck)

        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            gamePhase = GamePhase.MUS_DECISION,
            currentTurnPlayerId = players.first().id, // The first player starts
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    fun onCardSelected(card: Card) {
        val currentSelection = _gameState.value.selectedCardsForDiscard
        val newSelection = if (card in currentSelection) {
            currentSelection - card // If already selected, deselect it
        } else {
            currentSelection + card // If not selected, select it
        }
        // Update the state with the new selection
        _gameState.value = _gameState.value.copy(selectedCardsForDiscard = newSelection)
    }
}