package com.doselfurioso.musvisto.presentation


import android.util.Log
import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// This annotation tells Hilt that this is a ViewModel and it can be injected.
@HiltViewModel
class GameViewModel @Inject constructor(
    private val logic: MusGameLogic // Hilt will automatically provide our MusGameLogic instance here.
) : ViewModel() {

    // This is the private, mutable version of our game state. Only the ViewModel can change it.
    private val _gameState = MutableStateFlow(GameState())

    // This is the public, read-only version. The UI will observe this for changes.
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    init {
        // This block runs when the ViewModel is first created.
        // Let's set up an initial game state.
        startNewGame()
    }

    // NEW: Function to handle user actions from the UI
    fun onAction(action: GameAction) {
        Log.d("MusVistoTest", "Action received: ${action.displayText}")
        // For now, we just log the action.
        // In the next step, we'll connect this to MusGameLogic to update the state.
    }

    private fun startNewGame() {
        val players = listOf(
            Player(id = "p1", name = "Ana"),
            Player(id = "p2", name = "Luis"),
            Player(id = "p3", name = "Sara"),
            Player(id = "p4", name = "Juan")
        )
        val deck = logic.shuffleDeck(logic.createDeck())
        val (updatedPlayers, remainingDeck) = logic.dealCards(players, deck)

        // MODIFIED: We set the initial available actions
        _gameState.value = GameState(
            players = updatedPlayers,
            deck = remainingDeck,
            gamePhase = GamePhase.MUS_DECISION,
            // When the game starts, the player can either want Mus or not
            ///TODO descomentar
            //availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )

    }
}