package com.doselfurioso.musvisto.presentation


import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.MusGameLogic
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

    // We define who the human player is. In a real game, this would come from a login system.
    private val humanPlayerId = "p1"

    init {
        startNewGame()
    }

    fun onAction(action: GameAction) {
        val currentState = _gameState.value
        // The action is always performed by the human player
        val newState = logic.processAction(currentState, action, humanPlayerId)
        _gameState.value = newState

        // After our action, check if it's an AI's turn
        handleAiTurn()
    }

    private fun handleAiTurn() {
        val currentState = _gameState.value
        val currentPlayerId = currentState.currentTurnPlayerId
        val currentPlayer = currentState.players.find { it.id == currentPlayerId }

        // If the current player is an AI, it takes its turn
        if (currentPlayer != null && currentPlayer.isAi) {
            // TODO: In the future, we will call our AILogic here.
            // For now, let's make the AI always say "Mus" or "Paso"
            val aiAction = if (currentState.gamePhase == GamePhase.MUS_DECISION) {
                GameAction.Mus
            } else {
                GameAction.Paso
            }

            Log.d("MusVistoTest", "AI ${currentPlayer.name} performs action: ${aiAction.displayText}")

            // We need a small delay to make it feel like the AI is "thinking"
            viewModelScope.launch {
                delay(1500) // 1.5 second delay
                val newState = logic.processAction(currentState, aiAction,
                    currentPlayerId.toString()
                )
                _gameState.value = newState

                // Check again in case the next player is also an AI
                handleAiTurn()
            }
        }
    }

    private fun startNewGame() {
        val players = listOf(
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false),
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_aragon, isAi = true),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_navarra, isAi = true), // Let's make player 3 human for now
            Player(id = "p4", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true)
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
}