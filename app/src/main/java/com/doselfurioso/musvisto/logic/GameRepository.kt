package com.doselfurioso.musvisto.logic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.SaveState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


private const val PREFS_NAME = "MusVistoPrefs"
private const val KEY_SAVE_STATE = "save_state"
private const val KEY_SETTINGS = "game_settings"


/**
 * Implementación local de [GameStore]: persiste en `SharedPreferences` (JSON).
 * Es el backend de almacenamiento on-device; en multijugador un host autoritativo
 * podría usar otra implementación de [GameStore] sin tocar los ViewModels.
 */
class GameRepository  constructor(
    private val context: Context
) : GameStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveState(saveState: SaveState) {
        try {
            val jsonState = Json.encodeToString(saveState)
            prefs.edit().putString(KEY_SAVE_STATE, jsonState).apply()
        } catch (e: Exception) {
            Log.e("GameRepository", "Error saving state", e)
        }
    }

    override fun loadState(): SaveState? {
        return try {
            val jsonState = prefs.getString(KEY_SAVE_STATE, null) ?: return null
            Json.decodeFromString<SaveState>(jsonState)
        } catch (e: Exception) {
            Log.e("GameRepository", "Error loading state", e)
            null
        }
    }

    override fun deleteState() {
        prefs.edit().remove(KEY_SAVE_STATE).apply()
    }

    override fun saveSettings(settings: GameSettings) {
        try {
            prefs.edit().putString(KEY_SETTINGS, Json.encodeToString(settings)).apply()
        } catch (e: Exception) {
            Log.e("GameRepository", "Error saving settings", e)
        }
    }

    override fun loadSettings(): GameSettings {
        return try {
            val json = prefs.getString(KEY_SETTINGS, null) ?: return GameSettings()
            Json.decodeFromString<GameSettings>(json)
        } catch (e: Exception) {
            Log.e("GameRepository", "Error loading settings", e)
            GameSettings()
        }
    }
}