package com.doselfurioso.musvisto.logic

/**
 * Lado **host** del bucle multijugador (prep, sin red): conecta el [MatchHost]
 * autoritativo a un [MatchTransport].
 *
 * Al [start]: publica la vista redactada inicial de cada asiento y se suscribe a
 * los comandos entrantes; por cada comando, lo aplica al host y re-publica la
 * vista de TODOS los asientos (el estado público + la mano propia de cada uno).
 *
 * Si se pasa un [AiSeatDriver], tras cada comando (y al arrancar) el host
 * **auto-avanza los turnos de IA** host-side hasta que toca un humano o la ronda
 * se resuelve. Sigue sin cubrir (Fase 3, follow-up): el pacing/turn timers, las
 * transiciones de ronda (ROUND_OVER→siguiente, como el simulador), y la
 * autorización por asiento. Puro/JVM: testeable con un transporte en memoria.
 */
class MatchHostService(
    private val host: MatchHost,
    private val transport: MatchTransport,
    private val seatIds: List<String>,
    private val aiDriver: AiSeatDriver? = null
) {
    fun start() {
        transport.observeCommands { seatId, command ->
            host.submitCommand(seatId, command)
            advance()
            publishAllViews()
        }
        advance()
        publishAllViews()
    }

    /**
     * Avanza el estado host-side hasta que toque un humano o la ronda se resuelva:
     * resuelve las fases de SISTEMA (declaración, siempre) y, si hay [aiDriver],
     * aplica las decisiones de las IA en su turno. Cap anti-cuelgue.
     */
    private fun advance() {
        var steps = 0
        while (steps++ < MAX_AI_STEPS) {
            if (host.resolveSystemPhaseIfAny()) continue
            val next = aiDriver?.commandFor(host.authoritativeState) ?: break
            host.submitCommand(next.first, next.second)
        }
    }

    private fun publishAllViews() {
        seatIds.forEach { seatId -> transport.publishView(seatId, host.viewFor(seatId)) }
    }

    private companion object {
        /** Tope de acciones de IA encadenadas entre dos turnos humanos (anti-cuelgue). */
        const val MAX_AI_STEPS = 500
    }
}
