package com.doselfurioso.musvisto.presentation


import androidx.lifecycle.ViewModel
import com.doselfurioso.musvisto.logic.GameRepository
import com.doselfurioso.musvisto.model.GameSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Asientos de la mesa que el usuario puede personalizar (#34/#36). */
enum class TableSlot { HUMAN, PARTNER, RIVAL_LEFT, RIVAL_RIGHT }

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

    fun setBestOfChicos(bestOf: Int) = update { it.copy(bestOfChicos = bestOf) }

    fun setHumanName(name: String) = update { it.copy(humanName = name) }

    /**
     * Asigna [characterId] al [slot]. Para mantener los 4 asientos con personajes
     * DISTINTOS, si ese personaje ya ocupaba otro asiento se INTERCAMBIAN (el que
     * estaba en [slot] pasa al asiento que liberó). Como los defaults son distintos
     * y el roster tiene más personajes que asientos, nunca quedan duplicados.
     */
    fun assignCharacter(slot: TableSlot, characterId: String) {
        val s = _settings.value
        val current = linkedMapOf(
            TableSlot.HUMAN to s.humanCharacterId,
            TableSlot.PARTNER to s.partnerCharacterId,
            TableSlot.RIVAL_LEFT to s.rivalLeftCharacterId,
            TableSlot.RIVAL_RIGHT to s.rivalRightCharacterId
        )
        if (current[slot] == characterId) return
        val previousSlot = current.entries.firstOrNull { it.value == characterId }?.key
        val displaced = current.getValue(slot)
        current[slot] = characterId
        if (previousSlot != null) current[previousSlot] = displaced
        update {
            it.copy(
                humanCharacterId = current.getValue(TableSlot.HUMAN),
                partnerCharacterId = current.getValue(TableSlot.PARTNER),
                rivalLeftCharacterId = current.getValue(TableSlot.RIVAL_LEFT),
                rivalRightCharacterId = current.getValue(TableSlot.RIVAL_RIGHT)
            )
        }
    }

    private fun update(transform: (GameSettings) -> GameSettings) {
        val updated = transform(_settings.value)
        gameRepository.saveSettings(updated)
        _settings.value = updated
    }
}