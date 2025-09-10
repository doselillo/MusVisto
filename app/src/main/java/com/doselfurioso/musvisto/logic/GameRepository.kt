package com.doselfurioso.musvisto.logic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.doselfurioso.musvisto.model.GameState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "MusVistoPrefs"
private const val KEY_GAME_STATE = "game_state"

@Singleton
class GameRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveGameState(gameState: GameState) {
        try {
            val jsonState = Json.encodeToString(gameState)
            prefs.edit().putString(KEY_GAME_STATE, jsonState).apply()
        } catch (e: Exception) {
            Log.e("GameRepository", "Error saving game state", e)
        }
    }

    fun loadGameState(): GameState? {
        return try {
            val jsonState = prefs.getString(KEY_GAME_STATE, null) ?: return null
            Json.decodeFromString<GameState>(jsonState)
        } catch (e: Exception) {
            Log.e("GameRepository", "Error loading game state", e)
            null
        }
    }

    fun deleteGameState() {
        prefs.edit().remove(KEY_GAME_STATE).apply()
    }
}