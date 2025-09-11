package com.doselfurioso.musvisto.presentation


import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainMenuViewModel(private val gameRepository: GameRepository) : ViewModel() {

    private val _hasSavedGame = MutableStateFlow(false)
    val hasSavedGame = _hasSavedGame.asStateFlow()

    init {
        checkSavedGame()
    }

    fun checkSavedGame() {
        _hasSavedGame.value = gameRepository.loadState() != null
    }

    fun startNewGame() {
        gameRepository.deleteState()
    }
}