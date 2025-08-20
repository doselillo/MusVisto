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
            Player(id = "p1", name = "Ana", avatarResId = R.drawable.avatar_castilla, isAi = false, team = "teamA"),
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamB"),
            Player(id = "p3", name = "Sara", avatarResId = R.drawable.avatar_navarra, isAi = false, team = "teamA"), // Let's make player 3 human for now
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