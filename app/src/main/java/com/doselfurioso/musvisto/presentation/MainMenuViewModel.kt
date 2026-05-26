package com.doselfurioso.musvisto.presentation


import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.model.GameSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainMenuViewModel(private val gameRepository: GameRepository) : ViewModel() {

    private val _hasSavedGame = MutableStateFlow(false)
    val hasSavedGame = _hasSavedGame.asStateFlow()

    // #29 Fase 2: ajustes de reglas, editables desde la pantalla de Opciones.
    // Se aplican a la PRÓXIMA partida nueva (startNewGame en GameViewModel los
    // relee del repositorio); una partida en curso conserva sus reglas.
    private val _settings = MutableStateFlow(gameRepository.loadSettings())
    val settings = _settings.asStateFlow()

    init {
        checkSavedGame()
    }

    fun checkSavedGame() {
        _hasSavedGame.value = gameRepository.loadState() != null
    }

    fun startNewGame() {
        gameRepository.deleteState()
    }

    fun setBestOfChicos(bestOf: Int) {
        val updated = _settings.value.copy(bestOfChicos = bestOf)
        gameRepository.saveSettings(updated)
        _settings.value = updated
    }
}