package com.doselfurioso.musvisto.presentation


import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.AILogic
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
    private val logic: MusGameLogic,
    private val aiLogic: AILogic
) : ViewModel() {

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _isDebugMode = MutableStateFlow(false)
    val isDebugMode: StateFlow<Boolean> = _isDebugMode.asStateFlow()

    // We removed the hardcoded humanPlayerId
    val humanPlayerId = "p1"

    init {
        startNewGame()
    }

    fun onToggleDebugMode() {
        _isDebugMode.value = !_isDebugMode.value
    }

    //This function now takes the playerId as a parameter
    fun onAction(action: GameAction, playerId: String) {
        // NEW: Handle the Continue and NewGame actions first
        when (action) {
            is GameAction.Continue, is GameAction.NewGame -> {
                startNewGame()
                return
            }
            else -> {
                // The rest of the logic for normal game actions
                val currentState = _gameState.value
                if (currentState.gamePhase == GamePhase.GAME_OVER || currentState.gamePhase == GamePhase.SCORING) return

                val newState = logic.processAction(currentState, action, playerId)
                _gameState.value = newState

                if (newState.gamePhase == GamePhase.SCORING) {
                    finalizeRound(newState)
                } else {
                    handleAiTurn()
                }
            }
        }
    }

    private fun finalizeRound(roundEndState: GameState) {
        Log.d("MusVistoTest", "--- ROUND END --- SCORING ---")

        val scoredState = logic.scoreRound(roundEndState)
        Log.d("MusVistoTest", "FINAL SCORE: ${scoredState.score}")

        val scoreToWin = 40
        val teamAScore = scoredState.score["teamA"] ?: 0
        val teamBScore = scoredState.score["teamB"] ?: 0
        val winner = if (teamAScore >= scoreToWin) "teamA" else if (teamBScore >= scoreToWin) "teamB" else null

        if (winner != null) {
            // GAME OVER: Show winner and "New Game" button
            _gameState.value = scoredState.copy(
                gamePhase = GamePhase.GAME_OVER,
                winningTeam = winner,
                availableActions = listOf(GameAction.NewGame)
            )
        } else {
            // END OF ROUND: Show score and "Continue" button
            // We removed the automatic delay and restart
            _gameState.value = scoredState.copy(
                availableActions = listOf(GameAction.Continue)
            )
        }
    }

    private fun handleAiTurn() {
        val currentState = _gameState.value
        val currentPlayerId = currentState.currentTurnPlayerId
        val currentPlayer = currentState.players.find { it.id == currentPlayerId }

        // First, check if it's an AI's turn
        if (currentPlayer != null && currentPlayer.isAi) {

            val aiAction = aiLogic.makeDecision(currentState, currentPlayer)
            // THE KEY CHANGE IS HERE: The AI now chooses the correct action based on the phase
            if (aiAction is GameAction.ConfirmDiscard) {
                // For now, AI discards its first card. We'll make this smarter later.
                val cardToDiscard = currentPlayer.hand.first()
                _gameState.value = currentState.copy(selectedCardsForDiscard = setOf(cardToDiscard))
            }

            Log.d("MusVistoTest", "AI ${currentPlayer.name} performs action: ${aiAction.displayText}")

            viewModelScope.launch {
                delay(1000) // 1.5 second delay

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
            Player(id = "p2", name = "Luis", avatarResId = R.drawable.avatar_navarra, isAi = true, team = "teamB"),
            Player(id = "p3", "Sara", avatarResId = R.drawable.avatar_aragon, isAi = true, team = "teamA"),
            Player(id = "p4", name = "Juan", avatarResId = R.drawable.avatar_granada, isAi = true, team = "teamB")
        )

        val initialDeck = logic.createDeck()
        val shuffledDeck = logic.shuffleDeck(initialDeck)

        // --- DEBUG LOGS START ---
        Log.d("MusVistoDebug", "--- STARTING A NEW GAME ---")
        Log.d("MusVistoDebug", "Shuffled deck before dealing (${shuffledDeck.size} cards): $shuffledDeck")
        // --- DEBUG LOGS END ---

        val (updatedPlayers, remainingDeck) = logic.dealCards(players, shuffledDeck)

        // --- DEBUG LOGS START ---
        Log.d("MusVistoDebug", "--- DEALING COMPLETE ---")
        updatedPlayers.forEach { player ->
            Log.d("MusVistoDebug", "Hand dealt to ${player.name}: ${player.hand}")
        }
        Log.d("MusVistoDebug", "Deck after dealing (${remainingDeck.size} cards): $remainingDeck")
        // --- DEBUG LOGS END ---

        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            gamePhase = GamePhase.MUS_DECISION,
            currentTurnPlayerId = players.first().id,
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