package com.doselfurioso.musvisto.logic

/**
 * Logging desacoplado del motor (multijugador Fase 0.5).
 *
 * `MusGameLogic` deja de depender de `android.util.Log` y registra a través de
 * esta interfaz Kotlin pura → el reducer es reusable **host/server-side** (JVM
 * puro, p. ej. un servidor autoritativo en Cloud Run) sin arrastrar Android.
 *
 * El default es [NoOp] (silencioso: tests y server-side). En Android se inyecta
 * [AndroidGameLogger], que delega en `android.util.Log` con el mismo
 * comportamiento que antes.
 */
interface GameLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String)

    /** Implementación silenciosa por defecto (tests, JVM puro). */
    object NoOp : GameLogger {
        override fun d(tag: String, message: String) = Unit
        override fun e(tag: String, message: String) = Unit
    }
}
