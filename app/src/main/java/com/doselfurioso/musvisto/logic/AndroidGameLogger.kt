package com.doselfurioso.musvisto.logic

import android.util.Log

/**
 * Adaptador Android de [GameLogger] (multijugador Fase 0.5): delega en
 * `android.util.Log`. Aísla la única dependencia de Android que tenía el motor;
 * se inyecta en `MusGameLogic` desde la capa Android (MainActivity). En server-side
 * o tests se usa [GameLogger.NoOp] en su lugar.
 */
object AndroidGameLogger : GameLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
