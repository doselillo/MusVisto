package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.SaveState

/**
 * Abstracción de persistencia (multijugador Fase 0.4).
 *
 * Los ViewModels guardan/cargan a través de esta interfaz **sin saber** si el
 * backend es local (SharedPreferences → [GameRepository]) o en red (un futuro
 * `NetworkGameStore` que sincronice el estado del host autoritativo). Kotlin puro:
 * no arrastra Android, así que la capa de presentación deja de depender de un
 * detalle de almacenamiento concreto.
 */
interface GameStore {
    fun saveState(saveState: SaveState)
    fun loadState(): SaveState?
    fun deleteState()

    /** Ajustes de reglas globales (#29). Opciones los escribe; una partida nueva los lee. */
    fun saveSettings(settings: GameSettings)
    fun loadSettings(): GameSettings
}
