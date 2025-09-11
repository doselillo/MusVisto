package com.doselfurioso.musvisto.logic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.doselfurioso.musvisto.model.SaveState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


private const val PREFS_NAME = "MusVistoPrefs"
private const val KEY_SAVE_STATE = "save_state"


class GameRepository  constructor(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveState(saveState: SaveState) {
        try {
            val jsonState = Json.encodeToString(saveState)
            prefs.edit().putString(KEY_SAVE_STATE, jsonState).apply()
        } catch (e: Exception) {
            Log.e("GameRepository", "Error saving state", e)
        }
    }

    fun loadState(): SaveState? {
        return try {
            val jsonState = prefs.getString(KEY_SAVE_STATE, null) ?: return null
            Json.decodeFromString<SaveState>(jsonState)
        } catch (e: Exception) {
            Log.e("GameRepository", "Error loading state", e)
            null
        }
    }

    fun deleteState() {
        prefs.edit().remove(KEY_SAVE_STATE).apply()
    }
}