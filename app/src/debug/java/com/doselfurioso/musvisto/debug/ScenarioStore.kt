package com.doselfurioso.musvisto.debug

import android.content.Context
import android.util.Log
import com.doselfurioso.musvisto.model.DebugScenario
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistencia de escenarios creados en la app (solo build debug).
 *
 * Mismo patrón que `GameRepository`: JSON en SharedPreferences propios. No
 * existe en release (este archivo solo se compila en `src/debug/`), así que
 * nada de esto llega a producción.
 */
object ScenarioStore {
    private const val PREFS_NAME = "MusVistoDebugScenarios"
    private const val KEY_LIST = "scenarios"

    fun load(context: Context): List<DebugScenario> {
        return try {
            val json = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LIST, null) ?: return emptyList()
            Json.decodeFromString<List<DebugScenario>>(json)
        } catch (e: Exception) {
            Log.e("ScenarioStore", "Error cargando escenarios", e)
            emptyList()
        }
    }

    fun save(context: Context, scenarios: List<DebugScenario>) {
        try {
            val json = Json.encodeToString(scenarios)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LIST, json).apply()
        } catch (e: Exception) {
            Log.e("ScenarioStore", "Error guardando escenarios", e)
        }
    }

    /** Inserta o reemplaza por nombre (el nombre es la clave de edición). */
    fun upsert(context: Context, scenario: DebugScenario): List<DebugScenario> {
        val current = load(context).toMutableList()
        val idx = current.indexOfFirst { it.name == scenario.name }
        if (idx >= 0) current[idx] = scenario else current.add(scenario)
        save(context, current)
        return current
    }

    fun delete(context: Context, name: String): List<DebugScenario> {
        val current = load(context).filterNot { it.name == name }
        save(context, current)
        return current
    }
}
