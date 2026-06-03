package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
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
 * Ritmos (ms) del bucle host: [turnMs] entre acciones de IA (que el cliente las vea
 * jugar) y [roundOverMs] de "fin de ronda" visible antes de repartir la siguiente
 * (más largo, para leer el resultado). En tests, ambos 0 → transición síncrona.
 */
data class MatchPacing(val turnMs: Long = 0L, val roundOverMs: Long = turnMs)

/**
 * Lado **host** del bucle multijugador: conecta el [MatchHost] autoritativo a un
 * [MatchTransport].
 *
 * Al [start]: publica la vista redactada inicial de cada asiento y se suscribe a
 * los comandos entrantes; por cada comando, lo aplica al host, re-publica las
 * vistas y avanza los turnos de IA / fases de sistema.
 *
 * **Pacing (Fase 3b — IA visible):** el auto-avance corre en una corrutina sobre
 * [scope] y publica una vista TRAS CADA acción de IA, con [MatchPacing.turnMs] de
 * espera entre medias, para que el cliente VEA jugar a los rivales a ritmo humano. El job
 * de avance es ÚNICO y se cancela/relanza por comando (patrón "engine serializado",
 * ver KNOWN_ISSUES #1).
 *
 * **Robustez:** el primer paso del avance corre inline dentro del callback de
 * Firebase (Main.immediate); si `AILogic.makeDecision` —u otra pieza— lanzase ahí,
 * Firebase se tragaría la excepción y la mesa quedaría CONGELADA en silencio. Por eso
 * cada paso (aplicar comando, publicar, decidir IA) va envuelto: se registra vía
 * [log] y se cae a una acción válida segura, de modo que la partida nunca se atasca.
 *
 * **Transiciones de ronda (Fase 3c):** al llegar a `ROUND_OVER`, el bucle puntúa la
 * ronda host-side y publica el resultado; con humanos en mesa espera a que alguno pulse
 * "Continuar" para repartir la siguiente, y una mesa de SOLO IA la pacea el host (ver
 * [resolveRoundTransition]).
 *
 * Sigue sin cubrir (Fase 3/5, follow-up): turn timers / AFK, autorización por asiento,
 * y vacas/multi-chico (hoy llegar al chico = fin de partida). Puro/JVM: testeable con
 * un transporte en memoria y pacing en 0 (avance síncrono).
 */
class MatchHostService(
    private val host: MatchHost,
    private val transport: MatchTransport,
    private val seatIds: List<String>,
    private val aiDriver: AiSeatDriver? = null,
    private val scope: CoroutineScope,
    private val pacing: MatchPacing = MatchPacing(),
    private val log: (String) -> Unit = {}
) {
    private var advanceJob: Job? = null

    fun start() {
        transport.observeCommands { seatId, command ->
            // Pase lo que pase al aplicar/publicar el comando, SIEMPRE relanzamos el
            // avance: una excepción tragada por el callback de Firebase dejaría la IA
            // sin arrancar (cuelgue). La capturamos, la registramos y seguimos.
            runCatching {
                applyIncoming(seatId, command)
                publishAllViews()
            }.onFailure { log("submit/publish de $seatId falló: ${it.stackTraceToString()}") }
            launchAdvance()
        }
        runCatching { publishAllViews() }.onFailure { log("publish inicial falló: ${it.stackTraceToString()}") }
        launchAdvance()
    }

    /**
     * Aplica un comando entrante de un cliente. El `Continue` de fin de ronda NO pasa
     * por el reducer (no es una acción de juego): cualquier humano lo usa para repartir
     * la siguiente ronda. Si llega cuando ya NO es `ROUND_OVER` (otro humano se
     * adelantó, o llega duplicado), es obsoleto → se ignora. El resto de comandos van
     * al reducer como siempre.
     */
    private fun applyIncoming(seatId: String, command: GameCommand) {
        if (command == GameCommand.Continue) {
            if (host.authoritativeState.gamePhase == GamePhase.ROUND_OVER) host.dealNextRound()
            return
        }
        host.submitCommand(seatId, command)
    }

    /** Relanza el avance como job ÚNICO (cancela el anterior, hermano de [scope]). */
    private fun launchAdvance() {
        advanceJob?.cancel()
        advanceJob = scope.launch { advanceLoop() }
    }

    // Patrón "guarda + continue / break terminal": los dos continue (fase de sistema y
    // transición de ronda) y el break (no hay turno de IA → esperamos a un humano o fin
    // de partida) son intencionales y legibles; no se contorsiona el bucle por la regla.
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun advanceLoop() {
        var steps = 0
        while (steps++ < MAX_AI_STEPS && currentCoroutineContext().isActive) {
            if (resolveDeclaration()) {
                continue
            }
            if (resolveRoundTransition()) {
                steps = 0 // ronda nueva: presupuesto de pasos fresco (anti-cuelgue por ronda)
                continue
            }
            val next = nextAiCommand() ?: break
            if (pacing.turnMs > 0) delay(pacing.turnMs)
            runCatching {
                host.submitCommand(next.first, next.second)
                publishAllViews()
            }.onFailure { log("submit/publish de IA ${next.first} falló: ${it.stackTraceToString()}") }
        }
    }

    /**
     * Transición de ronda (Fase 3c): si el estado está en `ROUND_OVER`, lo PUNTÚA una
     * vez (la 1ª, aún sin desglose) y publica el resultado para que TODOS lo vean
     * (desglose + marcador + manos reveladas). Luego:
     *  - **partida terminada** (alguien ganó el chico) → no reparte; el bucle para en
     *    GAME_OVER.
     *  - **hay humanos en mesa** → NO reparte: espera a que alguno pulse "Continuar"
     *    (lo gestiona [start] al recibir el `Continue`). El bucle se detiene aquí.
     *  - **mesa de solo IA** → la pacea el host (espera [MatchPacing.roundOverMs] y
     *    reparte), porque no hay nadie que pulse y si no se congelaría.
     *
     * Devuelve `true` solo si REPARTIÓ (el bucle re-evalúa con presupuesto fresco).
     * Blindado: una excepción de la puntuación se registra y no se reparte (el estado
     * queda mostrable, sin spin).
     */
    private suspend fun resolveRoundTransition(): Boolean {
        if (host.authoritativeState.gamePhase != GamePhase.ROUND_OVER) return false
        if (host.authoritativeState.scoreBreakdown == null) { // aún sin puntuar
            val matchOver = try {
                host.scoreRoundOver()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("scoreRoundOver falló: ${e.stackTraceToString()}")
                return false
            }
            runCatching { publishAllViews() }.onFailure { log("publish (fin de ronda) falló: $it") }
            if (matchOver) return false // partida terminada (GAME_OVER ya publicado); el bucle para.
        }
        // Con un humano en mesa, el reparto lo dispara su "Continuar" (ver [start]); el
        // bucle se queda esperando. Solo una mesa de SOLO IA la auto-avanza el host.
        if (host.authoritativeState.players.any { !it.isAi }) return false
        if (pacing.roundOverMs > 0) delay(pacing.roundOverMs)
        runCatching {
            host.dealNextRound()
            publishAllViews()
        }.onFailure { log("dealNextRound falló: ${it.stackTraceToString()}") }
        return true
    }

    /**
     * Ronda de DECLARACIÓN (PARES_CHECK/JUEGO_CHECK): en vez de resolverla de golpe (era
     * INVISIBLE online), ANUNCIA cada "Tengo / No tengo" en orden de turno con pacing —para
     * que el cliente VEA la ronda, como en el modo local— y luego la resuelve host-side.
     * Devuelve true si gestionó la declaración. Blindado como el resto del bucle.
     */
    private suspend fun resolveDeclaration(): Boolean {
        val phase = host.authoritativeState.gamePhase
        if (phase != GamePhase.PARES_CHECK && phase != GamePhase.JUEGO_CHECK) return false
        val announcements = runCatching { host.declarationAnnouncements() }
            .onFailure { log("declarationAnnouncements falló: ${it.stackTraceToString()}") }
            .getOrDefault(emptyList())
        for ((seatId, command) in announcements) {
            if (pacing.turnMs > 0) delay(pacing.turnMs)
            runCatching {
                host.announce(seatId, command)
                publishAllViews()
            }.onFailure { log("anuncio de declaración ($seatId) falló: $it") }
        }
        if (pacing.turnMs > 0) delay(pacing.turnMs)
        runCatching {
            host.resolveSystemPhaseIfAny()
            publishAllViews()
        }.onFailure { log("resolveDeclaration (resolver) falló: ${it.stackTraceToString()}") }
        return true
    }

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
