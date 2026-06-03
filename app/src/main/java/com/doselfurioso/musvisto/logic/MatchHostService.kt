package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.toCommand
import kotlinx.coroutines.CancellationException
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
 * entre medias, para que el cliente VEA jugar a los rivales a ritmo humano. El job
 * de avance es ÚNICO y se cancela/relanza por comando (patrón "engine serializado",
 * ver KNOWN_ISSUES #1).
 *
 * **Robustez:** el primer paso del avance corre inline dentro del callback de
 * Firebase (Main.immediate); si `AILogic.makeDecision` —u otra pieza— lanzase ahí,
 * Firebase se tragaría la excepción y la mesa quedaría CONGELADA en silencio. Por eso
 * cada paso (aplicar comando, publicar, decidir IA) va envuelto: se registra vía
 * [log] y se cae a una acción válida segura, de modo que la partida nunca se atasca.
 *
 * Sigue sin cubrir (Fase 3, follow-up): turn timers / AFK, transiciones de ronda y
 * autorización por asiento. Puro/JVM: testeable con un transporte en memoria y
 * [pacingMs] = 0 (avance síncrono).
 */
class MatchHostService(
    private val host: MatchHost,
    private val transport: MatchTransport,
    private val seatIds: List<String>,
    private val aiDriver: AiSeatDriver? = null,
    private val scope: CoroutineScope,
    private val pacingMs: Long = 0L,
    private val log: (String) -> Unit = {}
) {
    private var advanceJob: Job? = null

    fun start() {
        transport.observeCommands { seatId, command ->
            // Pase lo que pase al aplicar/publicar el comando, SIEMPRE relanzamos el
            // avance: una excepción tragada por el callback de Firebase dejaría la IA
            // sin arrancar (cuelgue). La capturamos, la registramos y seguimos.
            runCatching {
                host.submitCommand(seatId, command)
                publishAllViews()
            }.onFailure { log("submit/publish de $seatId falló: ${it.stackTraceToString()}") }
            launchAdvance()
        }
        runCatching { publishAllViews() }.onFailure { log("publish inicial falló: ${it.stackTraceToString()}") }
        launchAdvance()
    }

    /** Relanza el avance como job ÚNICO (cancela el anterior, hermano de [scope]). */
    private fun launchAdvance() {
        advanceJob?.cancel()
        advanceJob = scope.launch { advanceLoop() }
    }

    private suspend fun advanceLoop() {
        var steps = 0
        while (steps++ < MAX_AI_STEPS && currentCoroutineContext().isActive) {
            if (resolveSystemPhase()) {
                runCatching { publishAllViews() }.onFailure { log("publish (sistema) falló: $it") }
                continue
            }
            val next = nextAiCommand() ?: break
            if (pacingMs > 0) delay(pacingMs)
            runCatching {
                host.submitCommand(next.first, next.second)
                publishAllViews()
            }.onFailure { log("submit/publish de IA ${next.first} falló: ${it.stackTraceToString()}") }
        }
    }

    private fun resolveSystemPhase(): Boolean =
        runCatching { host.resolveSystemPhaseIfAny() }
            .onFailure { log("resolveSystemPhase falló: ${it.stackTraceToString()}") }
            .getOrDefault(false)

    /**
     * Comando de la IA de turno, BLINDADO: si [AiSeatDriver.commandFor] (que corre
     * `AILogic.makeDecision`) lanza, lo registramos y caemos a una acción válida
     * segura del estado para que la mesa NO se congele. Devuelve null solo cuando no
     * hay turno de IA (humano / ROUND_OVER): el bucle se detiene legítimamente.
     */
    private fun nextAiCommand(): Pair<String, GameCommand>? {
        val driver = aiDriver ?: return null
        return try {
            driver.commandFor(host.authoritativeState)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val s = host.authoritativeState
            log("commandFor lanzó (turno=${s.currentTurnPlayerId}, fase=${s.gamePhase}): ${e.stackTraceToString()}")
            safeFallback(s)
        }
    }

    /** Acción válida más segura del estado para el asiento de turno (Paso > No quiero > primera). */
    private fun safeFallback(state: GameState): Pair<String, GameCommand>? {
        val turn = state.currentTurnPlayerId ?: return null
        val commands = state.availableActions.mapNotNull { it.toCommand() }
        val fallback = commands.firstOrNull { it is GameCommand.Pass }
            ?: commands.firstOrNull { it is GameCommand.Decline }
            ?: commands.firstOrNull()
            ?: return null
        log("fallback seguro para $turn: $fallback")
        return turn to fallback
    }

    private fun publishAllViews() {
        seatIds.forEach { seatId -> transport.publishView(seatId, host.viewFor(seatId)) }
    }

    private companion object {
        /** Tope de acciones de IA encadenadas entre dos turnos humanos (anti-cuelgue). */
        const val MAX_AI_STEPS = 500
    }
}
