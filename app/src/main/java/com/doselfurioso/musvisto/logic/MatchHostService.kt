package com.doselfurioso.musvisto.logic

/**
 * Lado **host** del bucle multijugador (prep, sin red): conecta el [MatchHost]
 * autoritativo a un [MatchTransport].
 *
 * Al [start]: publica la vista redactada inicial de cada asiento y se suscribe a
 * los comandos entrantes; por cada comando, lo aplica al host y re-publica la
 * vista de TODOS los asientos (el estado público + la mano propia de cada uno).
 *
 * **Alcance:** el flujo dirigido por las acciones de los CLIENTES. Sigue sin
 * cubrir (Fase 3, follow-up): orquestar los turnos de los asientos de IA (el host
 * correría `AILogic` y se auto-enviaría comandos), el pacing/turn timers, y la
 * autorización por asiento. Puro/JVM: testeable con un transporte en memoria.
 */
class MatchHostService(
    private val host: MatchHost,
    private val transport: MatchTransport,
    private val seatIds: List<String>
) {
    fun start() {
        transport.observeCommands { seatId, command ->
            host.submitCommand(seatId, command)
            publishAllViews()
        }
        publishAllViews()
    }

    private fun publishAllViews() {
        seatIds.forEach { seatId -> transport.publishView(seatId, host.viewFor(seatId)) }
    }
}
