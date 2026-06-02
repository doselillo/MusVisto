package com.doselfurioso.musvisto.presentation


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doselfurioso.musvisto.logic.AIArchetype
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.model.GameSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Asientos de la mesa que el usuario puede personalizar (#34/#36). */
enum class TableSlot { HUMAN, PARTNER, RIVAL_LEFT, RIVAL_RIGHT }

// El nombre es texto libre de alta frecuencia: persistir en cada tecla serializa
// JSON + escribe a disco. Se actualiza en memoria al instante y se guarda con
// debounce; commitSettings() fuerza el guardado al salir de la pantalla.
private const val NAME_SAVE_DEBOUNCE_MS = 400L

// Índices de asiento en la lista barajada de caras (partida rápida).
private const val SEAT_HUMAN = 0
private const val SEAT_PARTNER = 1
private const val SEAT_RIVAL_LEFT = 2
private const val SEAT_RIVAL_RIGHT = 3

class MainMenuViewModel(private val gameRepository: GameStore) : ViewModel() {

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

    private var nameSaveJob: Job? = null

    /**
     * Edita el nombre del humano: actualiza el StateFlow al instante (tecleo
     * fluido) y persiste con debounce, sin tocar disco en cada pulsación.
     */
    fun setHumanName(name: String) {
        _settings.value = _settings.value.copy(humanName = name)
        nameSaveJob?.cancel()
        nameSaveJob = viewModelScope.launch {
            delay(NAME_SAVE_DEBOUNCE_MS)
            gameRepository.saveSettings(_settings.value)
        }
    }

    /**
     * Persiste de inmediato cualquier cambio pendiente (el nombre con debounce).
     * Llamar ANTES de navegar fuera: GameViewModel relee `GameSettings` del disco
     * al arrancar, así que el último nombre tecleado debe estar ya guardado.
     */
    fun commitSettings() {
        nameSaveJob?.cancel()
        gameRepository.saveSettings(_settings.value)
    }

    /**
     * Asigna [characterId] al [slot]. Para mantener los 4 asientos con personajes
     * DISTINTOS, si ese personaje ya ocupaba otro asiento se INTERCAMBIAN (el que
     * estaba en [slot] pasa al asiento que liberó). Como los defaults son distintos
     * y el roster tiene al menos tantos personajes como asientos, nunca hay duplicados.
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

    /** Arquetipo (personalidad) actual de un asiento de IA. HUMAN no tiene → EQUILIBRADO. */
    fun archetypeFor(slot: TableSlot): AIArchetype = with(_settings.value) {
        when (slot) {
            TableSlot.HUMAN -> AIArchetype.EQUILIBRADO
            TableSlot.PARTNER -> AIArchetype.byName(partnerArchetype)
            TableSlot.RIVAL_LEFT -> AIArchetype.byName(rivalLeftArchetype)
            TableSlot.RIVAL_RIGHT -> AIArchetype.byName(rivalRightArchetype)
        }
    }

    /**
     * Rota la personalidad de un asiento de IA (flechas ‹ ›). El humano juega a
     * mano, así que no tiene arquetipo → no-op. El efecto en el juego lo conecta
     * la Fase C (hoy se persiste la elección; todas las IA juegan baseline).
     */
    fun rotateArchetype(slot: TableSlot, forward: Boolean) {
        if (slot == TableSlot.HUMAN) return
        val rotated = archetypeFor(slot).let { if (forward) it.next() else it.prev() }.name
        update {
            when (slot) {
                TableSlot.PARTNER -> it.copy(partnerArchetype = rotated)
                TableSlot.RIVAL_LEFT -> it.copy(rivalLeftArchetype = rotated)
                TableSlot.RIVAL_RIGHT -> it.copy(rivalRightArchetype = rotated)
                TableSlot.HUMAN -> it
            }
        }
    }

    /** Color del tapete de la mesa (#36); la clave la resuelve la UI (TableTheme). */
    fun setTableColor(colorKey: String) = update { it.copy(tableColor = colorKey) }

    /**
     * Partida rápida: caras DISTINTAS al azar en los 4 asientos + personalidad al
     * azar para los 3 rivales de IA. Conserva el nombre del humano. Persiste todo
     * y deja la mesa lista para arrancar sin pasar por la pantalla de selección.
     */
    fun randomizeTable() {
        val faces = CharacterRoster.all.map { it.id }.shuffled()
        val styles = AIArchetype.values()
        update {
            it.copy(
                humanCharacterId = faces[SEAT_HUMAN],
                partnerCharacterId = faces[SEAT_PARTNER],
                rivalLeftCharacterId = faces[SEAT_RIVAL_LEFT],
                rivalRightCharacterId = faces[SEAT_RIVAL_RIGHT],
                partnerArchetype = styles.random().name,
                rivalLeftArchetype = styles.random().name,
                rivalRightArchetype = styles.random().name
            )
        }
    }

    private fun update(transform: (GameSettings) -> GameSettings) {
        val updated = transform(_settings.value)
        gameRepository.saveSettings(updated)
        _settings.value = updated
    }
}