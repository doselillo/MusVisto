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
 * jugar), [roundOverMs] de "fin de ronda" visible antes de repartir la siguiente (más
 * largo, para leer el resultado) y la ventana visible de cada seña de IA online (Fase
 * 4.2). Esta última se desdobla, igual que `GameViewModel.gestureVisibleMs` offline:
 * [gesturePartnerMs] (legible) para la seña de una IA con COMPAÑERO HUMANO —el capitán
 * la lee para decidir el corte (#20)— y [gestureOtherMs] (flash corto) para el resto
 * (IA-IA, y rivales: un humano que CAZA una seña rival solo la ve un instante, como
 * offline, para no regalar lectura).
 *
 * [turnTimeoutMs] (Fase 3 — turn timer): tiempo que el host espera a un HUMANO antes de
 * jugar por él una acción segura (Paso/No quiero) o auto-Continuar en fin de ronda, para
 * que un jugador AFK no congele la mesa. **0 = desactivado** (tests y mesas sin red).
 *
 * En tests, todos 0 → transición síncrona y sin timeouts.
 */
data class MatchPacing(
    val turnMs: Long = 0L,
    val roundOverMs: Long = turnMs,
    val gesturePartnerMs: Long = turnMs,
    val gestureOtherMs: Long = turnMs,
    val turnTimeoutMs: Long = 0L
)

/**
 * Qué está esperando el host cuando se asienta (clasificación PURA del estado, ver
 * [MatchHostService.timeoutActionFor]). El turn timer actúa sobre esto; la POLÍTICA
 * de escalado (acción segura → cede a IA) la decide [MatchHostService.onTurnTimeout].
 */
internal sealed interface TimeoutAction {
    /** Turno de un HUMANO en un lance (con acciones): se le espera. */
    data class HumanTurn(val seatId: String) : TimeoutAction
    /** Fin de ronda esperando "Continuar": al agotar el tiempo, repartir la siguiente. */
    object DealNext : TimeoutAction
}

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

    // Turn timer (Fase 3): job que espera [MatchPacing.turnTimeoutMs] al humano de turno
    // y, si no actúa, juega por él (ver [scheduleTurnTimeout]). Único; se cancela/reprograma
    // con cada avance.
    private var turnTimeoutJob: Job? = null

    // AFK / cede a IA (rebanada 2): timeouts CONSECUTIVOS por asiento humano (se resetean
    // en cuanto ese asiento manda un comando = está presente). Al llegar a [AFK_THRESHOLD]
    // el asiento entra en [afkSeats] y lo conduce su cerebro de IA hasta que el humano vuelve.
    private val consecutiveTimeouts = mutableMapOf<String, Int>()
    private val afkSeats = mutableSetOf<String>()

    // Señas online (Fase 4.2): true tras planificar/mostrar las señas de la entrada
    // ACTUAL a MUS; se resetea al salir de MUS para re-planificar tras descarte / nueva
    // ronda (igual que offline re-corre onEnterMusPhase). Ver [resolveAiGestures].
    private var gesturesResolved = false

    // Vacas (#29): true tras contabilizar el chico de un ÓRDAGO ganado (GAME_OVER del reducer);
    // anti-recuento. Se resetea al salir de GAME_OVER. Ver [resolveOrdagoChicoEnd].
    private var ordagoSettled = false

    fun start() {
        transport.observeCommands { seatId, command ->
            // Un comando de un asiento = ese jugador está PRESENTE → sale de AFK y se le
            // reinicia el contador de timeouts (cede a IA, rebanada 2).
            afkSeats.remove(seatId)
            consecutiveTimeouts.remove(seatId)
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
            applyContinue()
            return
        }
        if (command == GameCommand.ShowGesture) {
            revealHumanGesture(seatId)
            return
        }
        if (command == GameCommand.NewGame) {
            startNewMatch()
            return
        }
        host.submitCommand(seatId, command)
    }

    /**
     * "Jugar de Nuevo" (paridad #1): NO pasa por el reducer (no re-arranca partida).
     * Solo se honra cuando la VACA ha terminado (`winningTeam != null`) — es justo cuando
     * el overlay ofrece el botón (con la vaca viva muestra "Siguiente chico" → `Continue`),
     * así un comando rezagado o un cliente que se adelanta no resetea una partida en curso.
     * Re-arranca host-side y limpia los flags por-partida; [start] re-publica y relanza el
     * avance, como con `Continue`.
     */
    private fun startNewMatch() {
        if (host.authoritativeState.winningTeam == null) return
        host.startNewMatch()
        gesturesResolved = false
        ordagoSettled = false
        afkSeats.clear()
        consecutiveTimeouts.clear()
    }

    /**
     * Avanza tras un fin de ronda / chico / partida — espejo del `when` de Continue offline
     * (#29 vacas). Lo dispara "Continuar" (un humano), el turn timer (AFK) o el auto-pacing de
     * una mesa de solo IA: `winningTeam`→fin de partida; `chicoJustWon`→chico nuevo 0-0; ROUND_OVER
     * normal→siguiente ronda. Un Continue obsoleto (fase ya avanzada) no hace nada (guard del `when`).
     */
    private fun applyContinue() {
        val s = host.authoritativeState
        when {
            s.winningTeam != null -> host.finishGame()
            s.chicoJustWon != null -> host.dealNextChico()
            s.gamePhase == GamePhase.ROUND_OVER -> host.dealNextRound()
        }
    }

    /**
     * Seña de un HUMANO (Fase 4.3): NO pasa por el reducer (no es acción de juego). El host
     * la computa VERAZ y la muestra ([MatchHost.showHumanGesture]); la publicación inicial la
     * hace [start] tras `applyIncoming`. Como la seña llega ASYNC (callback de Firebase, no
     * desde el bucle de avance donde se pacean las de IA), la ventana visible la cierra un job
     * aparte: tras [MatchPacing.gesturePartnerMs]/[gestureOtherMs] (legible si el emisor tiene
     * compañero humano; si no, flash) limpia la seña —solo si SIGUE siendo la suya, para no
     * pisar una seña/acción posterior—. En tests (ventana 0) no auto-limpia: la seña queda asertable.
     */
    private fun revealHumanGesture(seatId: String) {
        host.showHumanGesture(seatId) ?: return
        val window = if (hasHumanPartner(seatId)) pacing.gesturePartnerMs else pacing.gestureOtherMs
        if (window <= 0) return
        scope.launch {
            delay(window)
            if (host.authoritativeState.activeGesture?.playerId == seatId) {
                runCatching {
                    host.clearActiveGesture()
                    publishAllViews()
                }.onFailure { log("clear seña humana ($seatId) falló: ${it.stackTraceToString()}") }
            }
        }
    }

    /** Relanza el avance como job ÚNICO (cancela el anterior, hermano de [scope]). */
    private fun launchAdvance() {
        // Cualquier avance/comando resetea el reloj de turno: lo reprograma [advanceLoop]
        // al asentarse de nuevo (cada turno humano recibe un timeout fresco).
        turnTimeoutJob?.cancel()
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
            if (resolveAiGestures()) {
                continue
            }
            if (resolveDeclaration()) {
                continue
            }
            if (resolveOrdagoChicoEnd()) {
                steps = 0 // chico nuevo tras órdago: presupuesto de pasos fresco
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
        // El bucle se asentó esperando a un humano (turno en lance o "Continuar" en fin de
        // ronda): arranca el reloj de turno por si está AFK.
        scheduleTurnTimeout()
    }

    /**
     * Turn timer (Fase 3): al asentarse el bucle esperando a un humano, programa un job que
     * —tras [MatchPacing.turnTimeoutMs]— juega por él si sigue sin actuar (acción segura en
     * un lance, o auto-Continuar en fin de ronda; ver [timeoutActionFor]). Desactivado si
     * `turnTimeoutMs <= 0` (tests). Se cancela en [launchAdvance] con el siguiente avance.
     */
    private fun scheduleTurnTimeout() {
        if (pacing.turnTimeoutMs <= 0) return
        if (timeoutActionFor(host.authoritativeState) == null) return // no esperamos a un humano
        turnTimeoutJob = scope.launch {
            delay(pacing.turnTimeoutMs)
            onTurnTimeout()
        }
    }

    /** Dispara el timeout: re-evalúa el estado (pudo cambiar) y actúa por el humano. Blindado. */
    private fun onTurnTimeout() {
        when (val wait = timeoutActionFor(host.authoritativeState)) {
            is TimeoutAction.HumanTurn -> handleHumanTimeout(wait.seatId)
            TimeoutAction.DealNext -> {
                log("turn timeout: fin de ronda/chico sin Continuar → auto-avanzar")
                runCatching {
                    applyContinue() // #29: fin de partida / chico nuevo / siguiente ronda
                    publishAllViews()
                }.onFailure { log("turn timeout (continuar) falló: ${it.stackTraceToString()}") }
            }
            null -> return // el humano actuó justo a tiempo (o ya no procede): nada que hacer
        }
        launchAdvance()
    }

    /**
     * Política de AFK (rebanada 2): el humano [seatId] agotó su tiempo. Mientras no acumule
     * [AFK_THRESHOLD] timeouts CONSECUTIVOS, el host juega por él una acción SEGURA (Paso/No
     * quiero) y le deja seguir al mando. Al llegar al umbral —y si hay cerebro para conducirlo—
     * lo marca AFK: a partir de ahí su asiento lo lleva la IA (a ritmo normal, no 45 s/turno)
     * hasta que el humano vuelva (cualquier comando suyo lo reclama, ver [start]). Sin cerebro
     * (p. ej. tests), se queda en acción segura indefinidamente (nunca congela).
     */
    private fun handleHumanTimeout(seatId: String) {
        val timeouts = (consecutiveTimeouts[seatId] ?: 0) + 1
        consecutiveTimeouts[seatId] = timeouts
        if (timeouts >= AFK_THRESHOLD && aiDriver?.canDrive(seatId) == true) {
            afkSeats.add(seatId)
            log("turn timeout: $seatId AFK ($timeouts consecutivos) → cede a IA")
            return // el bucle ([nextAiCommand] con afkSeats) lo conducirá
        }
        val safe = safeFallback(host.authoritativeState) ?: return
        log("turn timeout: $seatId → ${safe.second} (acción segura, $timeouts/$AFK_THRESHOLD)")
        runCatching {
            host.submitCommand(safe.first, safe.second)
            publishAllViews()
        }.onFailure { log("turn timeout (safe move) falló: ${it.stackTraceToString()}") }
    }

    /**
     * Clasificación PURA de lo que el host espera al asentarse (la usa el scheduling y el
     * disparo del timeout):
     *  - `ROUND_OVER` (fin de ronda/chico esperando "Continuar") → [TimeoutAction.DealNext].
     *  - `GAME_OVER` con `chicoJustWon` (#29: chico ganado por ÓRDAGO, la vaca sigue) → DealNext
     *    (auto-continúa al chico nuevo si el humano está AFK).
     *  - turno de un HUMANO con acciones legales → [TimeoutAction.HumanTurn].
     *  - turno de IA / fin de partida (vaca) / sin acciones → null (no hay humano a quien esperar).
     */
    internal fun timeoutActionFor(state: GameState): TimeoutAction? {
        // Esperando "Continuar": fin de ronda/chico (ROUND_OVER), o chico ganado por órdago
        // con la vaca aún viva (GAME_OVER + chicoJustWon).
        val waitingToContinue = state.gamePhase == GamePhase.ROUND_OVER ||
            (state.gamePhase == GamePhase.GAME_OVER && state.chicoJustWon != null)
        if (waitingToContinue) return TimeoutAction.DealNext
        val turn = state.currentTurnPlayerId ?: return null
        val player = state.players.find { it.id == turn } ?: return null
        if (player.isAi) return null
        if (state.availableActions.mapNotNull { it.toCommand() }.isEmpty()) return null
        return TimeoutAction.HumanTurn(turn)
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
    /**
     * Vacas (#29) — camino del ÓRDAGO: el reducer deja GAME_OVER + `ordagoInfo` al ganarse un
     * órdago (= ganó el CHICO). El host lo reinterpreta en clave de vaca
     * ([MatchHost.applyOrdagoChicoWin]): contabiliza el chico UNA vez (guard [ordagoSettled],
     * reseteado al salir de GAME_OVER) → vaca terminada (`winningTeam`, para) o chico pendiente
     * (`chicoJustWon`). Con humanos espera "Continuar"; una mesa de SOLO IA auto-reparte el chico
     * nuevo (si no, se congelaría). Devuelve true solo si REPARTIÓ. Blindado.
     */
    private suspend fun resolveOrdagoChicoEnd(): Boolean {
        val state = host.authoritativeState
        if (state.gamePhase != GamePhase.GAME_OVER) {
            ordagoSettled = false
            return false
        }
        if (state.ordagoInfo == null || ordagoSettled) return false
        ordagoSettled = true
        runCatching {
            host.applyOrdagoChicoWin()
            publishAllViews()
        }.onFailure { log("applyOrdagoChicoWin falló: ${it.stackTraceToString()}") }
        val after = host.authoritativeState
        // Vaca terminada → para; humanos → esperan "Continuar"; solo-IA → auto-reparte el chico nuevo.
        if (after.chicoJustWon == null || after.players.any { !it.isAi }) return false
        if (pacing.roundOverMs > 0) delay(pacing.roundOverMs)
        runCatching {
            applyContinue() // chicoJustWon → dealNextChico
            publishAllViews()
        }.onFailure { log("auto-avance (órdago→chico) falló: ${it.stackTraceToString()}") }
        return true
    }

    private suspend fun resolveRoundTransition(): Boolean {
        if (host.authoritativeState.gamePhase != GamePhase.ROUND_OVER) return false
        if (host.authoritativeState.scoreBreakdown == null) { // aún sin puntuar
            try {
                host.scoreRoundOver() // #29: puntúa + contabiliza chico (chicoJustWon / winningTeam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("scoreRoundOver falló: ${e.stackTraceToString()}")
                return false
            }
            runCatching { publishAllViews() }.onFailure { log("publish (fin de ronda) falló: $it") }
        }
        // Con un humano en mesa, el avance lo dispara su "Continuar" (ver [start]/[applyContinue]);
        // el bucle se queda esperando. Solo una mesa de SOLO IA la auto-avanza el host (#29:
        // fin de partida / chico nuevo / siguiente ronda, según el estado puntuado).
        if (host.authoritativeState.players.any { !it.isAi }) return false
        if (pacing.roundOverMs > 0) delay(pacing.roundOverMs)
        runCatching {
            applyContinue()
            publishAllViews()
        }.onFailure { log("auto-avance de fin de ronda falló: ${it.stackTraceToString()}") }
        // Solo "avanzó de verdad" (presupuesto fresco) si NO terminó la partida.
        return host.authoritativeState.gamePhase != GamePhase.GAME_OVER
    }

    /**
     * Señas de IA online (Fase 4.2): al entrar a MUS, planifica las señas host-side y
     * PACEA su reveal (set→publish→delay→clear→publish) para que el cliente las VEA, como
     * el modo local en `triggerAiGestures`. La redacción por asiento ([StateRedactor])
     * decide QUIÉN ve cada una (compañero siempre, rival solo si la caza). Se resuelve UNA
     * vez por entrada a MUS ([gesturesResolved] se resetea al salir de MUS → re-planifica
     * tras descarte / nueva ronda). En Mus corrido no hay señas (lo respeta el planificador,
     * `pending` vacío). Devuelve true si planificó/paceó algo (el bucle re-evalúa). Blindado
     * como el resto del bucle (Firebase se traga las excepciones de sus callbacks).
     */
    private suspend fun resolveAiGestures(): Boolean {
        if (host.authoritativeState.gamePhase != GamePhase.MUS) {
            gesturesResolved = false
            return false
        }
        if (gesturesResolved) return false
        gesturesResolved = true
        val pending = runCatching { host.planGestures() }
            .onFailure { log("planGestures falló: ${it.stackTraceToString()}") }
            .getOrDefault(emptyMap())
        if (pending.isEmpty()) return false
        for ((seatId, kind) in pending) {
            runCatching {
                host.showGesture(seatId, kind)
                publishAllViews()
            }.onFailure { log("showGesture ($seatId) falló: ${it.stackTraceToString()}") }
            // Legible solo si la emite una IA con compañero HUMANO (el capitán la lee
            // para el corte #20); si no, flash corto → un rival que la caza no la lee gratis.
            val visibleMs = if (hasHumanPartner(seatId)) pacing.gesturePartnerMs else pacing.gestureOtherMs
            if (visibleMs > 0) delay(visibleMs)
            runCatching {
                host.clearActiveGesture()
                publishAllViews()
            }.onFailure { log("clearActiveGesture ($seatId) falló: $it") }
        }
        return true
    }

    /** ¿El asiento [seatId] tiene de compañero a un HUMANO? (decide la ventana visible de su seña). */
    private fun hasHumanPartner(seatId: String): Boolean {
        val players = host.authoritativeState.players
        val team = players.find { it.id == seatId }?.team ?: return false
        return players.any { it.id != seatId && it.team == team && !it.isAi }
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
            driver.commandFor(host.authoritativeState, afkSeats)
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

        /** Timeouts CONSECUTIVOS de un humano antes de cederle el asiento a la IA (AFK). */
        const val AFK_THRESHOLD = 2
    }
}
