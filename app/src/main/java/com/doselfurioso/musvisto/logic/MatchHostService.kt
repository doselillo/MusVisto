package com.doselfurioso.musvisto.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lado **host** del bucle multijugador: conecta el [MatchHost] autoritativo a un
 * [MatchTransport].
 *
 * Al [start]: publica la vista redactada inicial de cada asiento y se suscribe a
 * los comandos entrantes; por cada comando, lo aplica al host, re-publica las
 * vistas y avanza los turnos de IA / fases de sistema.
 *
 * **Pacing (Fase 3b — IA visible):** el auto-avance corre en una corrutina sobre
 * [scope] y publica una vista TRAS CADA acción de IA, con [pacingMs] de espera
 * entre medias, para que el cliente VEA jugar a los rivales a ritmo humano (en vez
 * de un salto instantáneo). El cliente queda "tonto": solo renderiza la vista que
 * le llega — el ritmo es del host (fuente única de verdad y de tiempo). El job de
 * avance es ÚNICO y se cancela/relanza por comando (patrón "engine serializado",
 * ver KNOWN_ISSUES #1): un comando entrante mata el avance rezagado.
 *
 * Sigue sin cubrir (Fase 3, follow-up): turn timers / AFK, transiciones de ronda
 * (ROUND_OVER→siguiente reparto) y autorización por asiento. Puro/JVM: testeable
 * con un transporte en memoria y [pacingMs] = 0 (avance síncrono, sin delays).
 */
class MatchHostService(
    private val host: MatchHost,
    private val transport: MatchTransport,
    private val seatIds: List<String>,
    private val aiDriver: AiSeatDriver? = null,
    private val scope: CoroutineScope,
    private val pacingMs: Long = 0L
) {
    private var advanceJob: Job? = null

    fun start() {
        transport.observeCommands { seatId, command ->
            host.submitCommand(seatId, command)
            publishAllViews()   // muestra YA la acción del jugador (sin espera)
            launchAdvance()     // y pacea la respuesta de la IA
        }
        publishAllViews()
        launchAdvance()
    }

    /** Relanza el avance como job ÚNICO (cancela el anterior, hermano de [scope]). */
    private fun launchAdvance() {
        advanceJob?.cancel()
        advanceJob = scope.launch { advanceLoop() }
    }

    /**
     * Avanza hasta que toque un humano o la ronda se resuelva: resuelve las fases
     * de SISTEMA (declaración, sin espera —no es una "acción" de jugador—) y, si hay
     * [aiDriver], aplica las decisiones de IA en su turno con [pacingMs] de pausa
     * ANTES de cada una (para que se vea). Cap anti-cuelgue.
     */
    private suspend fun advanceLoop() {
        var steps = 0
        while (steps++ < MAX_AI_STEPS && currentCoroutineContext().isActive) {
            if (host.resolveSystemPhaseIfAny()) {
                publishAllViews()
                continue
            }
            val next = aiDriver?.commandFor(host.authoritativeState) ?: break
            if (pacingMs > 0) delay(pacingMs)
            host.submitCommand(next.first, next.second)
            publishAllViews()
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
