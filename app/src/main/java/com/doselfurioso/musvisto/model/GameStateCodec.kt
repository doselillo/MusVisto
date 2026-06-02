package com.doselfurioso.musvisto.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serialización del [GameState] para la VISTA redactada que el host publica por
 * asiento (`views/{seat}` en RTDB, ver docs/context/MULTIPLAYER_PLAN.md). Espejo
 * de [GameCommandCodec] para el payload de estado.
 *
 * JSON kotlinx estable e independiente de Android. Los campos `@Transient` de
 * `GameState` (`availableActions`, `lastAction`, `currentLanceActions`, …) NO
 * viajan por diseño: el host los recalcula por asiento. `ignoreUnknownKeys`
 * deja que un host más nuevo añada campos sin romper a un cliente más viejo.
 */
object GameStateCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(state: GameState): String = json.encodeToString(state)

    fun decode(text: String): GameState = json.decodeFromString(text)
}
