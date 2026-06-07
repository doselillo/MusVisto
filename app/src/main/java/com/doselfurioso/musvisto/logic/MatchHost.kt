package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.ActiveGestureInfo
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.GestureKind
import com.doselfurioso.musvisto.model.LastActionView
import com.doselfurioso.musvisto.model.toAction
import com.doselfurioso.musvisto.model.toCommand
import kotlin.random.Random

/**
 * Núcleo **host-autoritativo** del multijugador (prep, sin red).
 *
 * Mantiene el `GameState` COMPLETO y aplica los comandos de los asientos a través
 * del reducer ya existente (`MusGameLogic.processAction`), exponiendo una **vista
 * redactada por asiento** (fog of war). Es exactamente la pieza que un
 * `FirebaseMatchHost` envolvería: el listener de `actions/{seatId}` llamaría a
 * [submitCommand] y luego escribiría `viewFor(s)` en `views/{s}` para cada asiento.
 *
 * Compone los tres primitivos de la Fase 0:
 *  - [GameCommand] (0.1) como entrada serializable de red,
 *  - `MusGameLogic.processAction` (reducer puro) como autoridad,
 *  - [StateRedactor] (0.3) como frontera de información de salida.
 *
 * **Alcance deliberado:** la rebanada APLICAR-COMANDO + REDACTAR. NO orquesta los
 * turnos de IA ni el pacing por `delay()` (eso vive en `GameViewModel` y se
 * reescribe a flujo de red + turn timers en la Fase 3). **Autorización mínima**
 * (¿puede este asiento esta acción AHORA?) queda como Fase 3: usaría las
 * `availableActions` recalculadas por asiento, hoy `@Transient`. Kotlin puro:
 * sin Android, sin Firebase → testeable y reusable host/server-side.
 */
class MatchHost(
    private val gameLogic: MusGameLogic,
    initialState: GameState,
    // Señas online (Fase 4.2): aleatoriedad de la PRE-DECISIÓN de señas (qué IA pasan
    // seña al entrar a MUS). Separada del rng de barajado del motor para no acoplar
    // streams; inyectable para tests deterministas. Default = Random.Default.
    private val rng: Random = Random.Default
) {
    var authoritativeState: GameState = initialState
        private set

    /**
     * Aplica el comando de [seatId] al estado autoritativo. El descarte es
     * autónomo (lleva sus cartas), que se inyectan en `selectedCardsForDiscard`
     * antes de reducir, igual que hace hoy el flujo de la IA.
     */
    fun submitCommand(seatId: String, command: GameCommand): GameState {
        val stateForAction = when (command) {
            is GameCommand.Discard -> authoritativeState.copy(
                selectedCardsForDiscard = command.cards.toSet()
            )
            else -> authoritativeState
        }
        authoritativeState = gameLogic.processAction(stateForAction, command.toAction(), seatId)
            .copy(lastActionView = LastActionView(seatId, command))
        return authoritativeState
    }

    /**
     * Resuelve host-side una fase de SISTEMA que no requiere acción de jugador: la
     * declaración de PARES_CHECK/JUEGO_CHECK, forzada por las manos (cada uno tiene
     * jugada o no, sin elección). El ViewModel local la resuelve igual vía
     * `resolveDeclaration` tras los anuncios visuales; host-side no hay UI, así que
     * se resuelve directa. Devuelve true si resolvió algo (el bucle debe re-evaluar).
     */
    fun resolveSystemPhaseIfAny(): Boolean {
        return when (authoritativeState.gamePhase) {
            GamePhase.PARES_CHECK, GamePhase.JUEGO_CHECK -> {
                authoritativeState = gameLogic.resolveDeclaration(authoritativeState)
                true
            }
            else -> false
        }
    }

    /**
     * Anuncios de la ronda de DECLARACIÓN (PARES_CHECK/JUEGO_CHECK): por cada jugador en
     * orden de turno, si tiene jugada o no (Tengo / No tengo) — lo mismo que offline muestra
     * en `GameViewModel.handleDeclarationSequence`. El host lo expone para PACEARLO host-side
     * (que el cliente VEA la ronda) antes de resolverla con [resolveSystemPhaseIfAny]; no muta
     * el estado (los anuncios los sella [announce] uno a uno). Vacío fuera de fase _CHECK.
     */
    fun declarationAnnouncements(): List<Pair<String, GameCommand>> {
        val state = authoritativeState
        val order = gameLogic.getTurnOrderedPlayers(state.players, state.manoPlayerId)
        return order.map { player ->
            val hasPlay = when (state.gamePhase) {
                GamePhase.PARES_CHECK -> gameLogic.getHandPares(player.hand).strength > 0
                GamePhase.JUEGO_CHECK -> gameLogic.getHandJuegoValue(player.hand) >= JUEGO_MIN
                else -> false
            }
            player.id to if (hasPlay) GameCommand.Tengo else GameCommand.NoTengo
        }
    }

    /** Sella una acción SOLO para anunciarla en el cliente (no pasa por el reducer). */
    fun announce(seatId: String, command: GameCommand) {
        authoritativeState = authoritativeState.copy(lastActionView = LastActionView(seatId, command))
    }

    /**
     * Transición de ronda (Fase 3c), parte 1: **PUNTUAR**. Cuando el reducer llega
     * a `ROUND_OVER` (tras el último lance), replica host-side la contabilidad que
     * en local hace `GameViewModel.processEndOfRound`: aplica `scoreRound`
     * (desglose), suma el delta al marcador EXCLUYENDO los eventos instantáneos (la
     * "no querida" ya se sumó durante la ronda → evita el doble conteo, #24/#30) y
     * decide si un equipo ha ganado el CHICO (`>= settings.pointsPerChico`).
     *
     * Deja el estado autoritativo en su forma "fin de ronda mostrable": desglose +
     * marcador al día + manos reveladas, **sin acciones de juego** (el "Continuar" lo
     * ofrece la UI de fin de ronda, no el reducer; ver [MatchHostService]). Devuelve
     * `true` si la PARTIDA ha terminado (alguien llegó al chico) → el orquestador para;
     * `false` si hay que repartir otra ronda ([dealNextRound]).
     *
     * **Alcance MVP online (rebanada 3c.1):** un solo chico. Vacas/multi-chico y el
     * camino órdago→GAME_OVER→chico nuevo (que en local hace `applyChicoWin`) son
     * follow-up; aquí llegar al chico = fin de partida.
     */
    fun scoreRoundOver(): Boolean {
        val roundEndState = authoritativeState
        val stateWithBreakdown = gameLogic.scoreRound(roundEndState)
        val breakdown = stateWithBreakdown.scoreBreakdown
            // scoreRound siempre lo rellena; si faltara, terminamos en seguro (sin spin).
            ?: run {
                authoritativeState = roundEndState.copy(
                    gamePhase = GamePhase.GAME_OVER,
                    availableActions = emptyList(),
                    revealAllHands = true
                )
                return true
            }

        val instantA = roundEndState.scoreEvents.filter { it.teamId == "teamA" }.sumOf { it.detail.points }
        val instantB = roundEndState.scoreEvents.filter { it.teamId == "teamB" }.sumOf { it.detail.points }
        val pointsTeamA = breakdown.teamAScoreDetails.sumOf { it.points } - instantA
        val pointsTeamB = breakdown.teamBScoreDetails.sumOf { it.points } - instantB
        val newScore = mapOf(
            "teamA" to (roundEndState.score["teamA"] ?: 0) + pointsTeamA,
            "teamB" to (roundEndState.score["teamB"] ?: 0) + pointsTeamB
        )

        val scoredState = stateWithBreakdown.copy(score = newScore)
        val scoreToWin = roundEndState.settings.pointsPerChico
        val chicoWinner = when {
            (newScore["teamA"] ?: 0) >= scoreToWin -> "teamA"
            (newScore["teamB"] ?: 0) >= scoreToWin -> "teamB"
            else -> null
        }

        authoritativeState = if (chicoWinner != null) {
            // Chico ganado por tantos (#29 vacas): contabiliza el chico y decide si la vaca
            // termina (espejo de GameViewModel.applyChicoWin, isOrdago=false → queda ROUND_OVER).
            applyChicoWin(scoredState, chicoWinner, isOrdago = false)
        } else {
            scoredState.copy(
                gamePhase = GamePhase.ROUND_OVER,
                availableActions = emptyList(),
                revealAllHands = true
            )
        }
        return chicoWinner != null
    }

    /**
     * Contabilidad de CHICO (#29 vacas), espejo de `GameViewModel.applyChicoWin`. [baseState]
     * ya trae score/desglose/ordagoInfo; aquí solo se tocan los flags de chico/vaca y la fase:
     *  - vaca terminada (`chicosWon[winner] >= chicosToWinVaca`) → `winningTeam` (fin de partida).
     *  - vaca continúa → `chicoJustWon` (al Continuar arranca chico nuevo 0-0, [dealNextChico]).
     * El camino del órdago usa GAME_OVER (overlay de órdago); el de los tantos, ROUND_OVER.
     */
    private fun applyChicoWin(baseState: GameState, chicoWinner: String, isOrdago: Boolean): GameState {
        val newChicos = baseState.chicosWon +
            (chicoWinner to (baseState.chicosWon[chicoWinner] ?: 0) + 1)
        val vacaOver = (newChicos[chicoWinner] ?: 0) >= baseState.settings.chicosToWinVaca
        return baseState.copy(
            chicosWon = newChicos,
            winningTeam = if (vacaOver) chicoWinner else null,
            chicoJustWon = if (vacaOver) null else chicoWinner,
            gamePhase = if (isOrdago) GamePhase.GAME_OVER else GamePhase.ROUND_OVER,
            availableActions = emptyList(),
            revealAllHands = true
        )
    }

    /**
     * Transición de ronda (#29 vacas): un ÓRDAGO ganado deja el reducer en GAME_OVER +
     * `winningTeam` (= ganó el CHICO, no la partida). Lo reinterpreta en clave de vaca (espejo
     * de `GameViewModel.processOrdagoChicoEnd`): contabiliza el chico (`isOrdago=true`, mantiene
     * GAME_OVER) → vaca terminada (`winningTeam`) o chico nuevo pendiente (`chicoJustWon`).
     */
    fun applyOrdagoChicoWin() {
        val chicoWinner = authoritativeState.winningTeam ?: return
        authoritativeState = applyChicoWin(authoritativeState, chicoWinner, isOrdago = true)
    }

    /** Fin de partida (#29): la vaca terminó → GAME_OVER (lo dispara "Continuar" sobre `winningTeam`). */
    fun finishGame() {
        authoritativeState = authoritativeState.copy(
            gamePhase = GamePhase.GAME_OVER,
            availableActions = emptyList()
        )
    }

    /**
     * "Jugar de Nuevo" online (paridad #1): re-arranca una VACA desde cero reusando los
     * MISMOS jugadores/asientos y ajustes. Espejo de [OnlineMatchHost.start] (mano de apertura
     * ALEATORIA + mus corrido #17) pero in-place: marcador 0-0 y chicos a cero (defaults de
     * `GameState`, no se arrastran como en [dealNextRound]/[dealNextChico]). Lo dispara un humano
     * desde el overlay de fin de partida; lo intercepta [MatchHostService.applyIncoming] (como
     * `Continue` → NO pasa por el reducer, que no re-arranca partida).
     */
    fun startNewMatch() {
        val players = authoritativeState.players
        val manoId = players[rng.nextInt(players.size)].id
        val shuffled = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, remaining) = gameLogic.dealCards(players, shuffled, manoId)

        authoritativeState = GameState(
            players = dealt,
            deck = remaining,
            settings = authoritativeState.settings,
            gamePhase = GamePhase.MUS,
            manoPlayerId = manoId,
            currentTurnPlayerId = manoId,
            playersInLance = players.map { it.id }.toSet(),
            musCorrido = true,
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    /**
     * Transición de ronda (Fase 3c), parte 2: **REPARTIR** la siguiente. Replica el
     * reparto de `GameViewModel.startNewGame` (rota la mano un puesto en el orden de
     * turno, baraja y da 4 cartas) pero en **MUS PLANO sin señas** —coherente con
     * [OnlineMatchHost.start] y el resto del online; las señas son Fase 4. Arrastra
     * marcador y chicos.
     *
     * Construye un `GameState` NUEVO (no `copy`) → los acumuladores de ronda
     * (scoreEvents, roundHistory, agreedBets, breakdown, revealAllHands…) vuelven a
     * sus defaults vacíos, igual que un reparto inicial. Arrastra marcador y chicos.
     */
    fun dealNextRound() = deal(resetScore = false)

    /**
     * Reparte el primer reparto de un CHICO NUEVO (#29 vacas): como [dealNextRound] pero con
     * el **marcador a 0-0** (arrastra los `chicosWon`). Lo dispara "Continuar" sobre `chicoJustWon`.
     */
    fun dealNextChico() = deal(resetScore = true)

    private fun deal(resetScore: Boolean) {
        val players = authoritativeState.players
        val lastManoIndex = players.indexOfFirst { it.id == authoritativeState.manoPlayerId }
        val nextManoId = players[(lastManoIndex - 1 + players.size) % players.size].id

        val shuffled = gameLogic.shuffleDeck(gameLogic.createDeck())
        val (dealt, remaining) = gameLogic.dealCards(players, shuffled, nextManoId)

        authoritativeState = GameState(
            players = dealt,
            deck = remaining,
            settings = authoritativeState.settings,
            score = if (resetScore) mapOf("teamA" to 0, "teamB" to 0) else authoritativeState.score,
            chicosWon = authoritativeState.chicosWon,
            gamePhase = GamePhase.MUS,
            manoPlayerId = nextManoId,
            currentTurnPlayerId = nextManoId,
            playersInLance = players.map { it.id }.toSet(),
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    /**
     * Señas online (Fase 4.2), paso 1: **PLANIFICAR**. Pre-decide qué IA pasan seña al
     * entrar a MUS y lo guarda en `pendingGestures` (lo lee AILogic para delegar el corte
     * #20: si voy a señalizar, mi pareja humana decidirá; si no, juego mi mano). Devuelve
     * el mapa ordenado para que el orquestador PACEE el reveal visual. Mirror host-side de
     * `GameViewModel.onEnterMusPhase` (la decisión vive en [MusGameLogic.planAiGestures]).
     */
    fun planGestures(): Map<String, GestureKind> {
        val pending = gameLogic.planAiGestures(authoritativeState, rng)
        authoritativeState = authoritativeState.copy(pendingGestures = pending)
        return pending
    }

    /**
     * Señas online (Fase 4.2), paso 2: **MOSTRAR** la seña de [seatId]. Mirror de
     * `GameViewModel.onAction(ShowGesture)`: activa la seña (parte visual) y la recuerda en
     * `knownGestures` (lo que la IA usa para apoyar al compañero / interceptar al rival). La
     * seña es VERAZ (la pre-decidida en [planGestures], derivada de la mano → no se puede
     * mentir). QUIÉN la ve lo decide la redacción por asiento ([StateRedactor]); aquí el host
     * la pone en el estado autoritativo completo.
     */
    fun showGesture(seatId: String, kind: GestureKind) {
        val gesture = ActiveGestureInfo(seatId, kind)
        authoritativeState = authoritativeState.copy(
            activeGesture = gesture,
            knownGestures = authoritativeState.knownGestures + (seatId to gesture)
        )
    }

    /** Señas online (Fase 4.2): agota la ventana visible de la seña activa (mirror del timer offline). */
    fun clearActiveGesture() {
        authoritativeState = authoritativeState.copy(activeGesture = null)
    }

    /**
     * Señas online (Fase 4.3): el HUMANO [seatId] pasa su seña. El host la computa VERAZ
     * de su mano (`determineGesture`, no se puede mentir, igual que las de IA) y la muestra
     * vía [showGesture]. Devuelve el kind mostrado, o null si la mano no es señalizable o
     * estamos en Mus corrido (#17, sin señas). QUIÉN la ve lo decide la redacción por asiento.
     */
    fun showHumanGesture(seatId: String): GestureKind? {
        if (authoritativeState.musCorrido) return null
        val player = authoritativeState.players.find { it.id == seatId } ?: return null
        val kind = gameLogic.determineGesture(player.hand) ?: return null
        showGesture(seatId, kind)
        return kind
    }

    /**
     * Vista redactada (fog of war) para un asiento + sus comandos legales. Los
     * comandos son el espejo serializable de `availableActions` (`@Transient` → no
     * viaja por la red, el cliente NO los puede recalcular): el host los proyecta a
     * [GameCommand] por asiento. Solo el asiento de TURNO recibe comandos; los
     * demás, lista vacía (mismo gating que el juego local, `currentTurnPlayerId`).
     */
    fun viewFor(seatId: String): GameState {
        val redacted = StateRedactor.redactFor(seatId, authoritativeState)
        val commands = if (seatId == authoritativeState.currentTurnPlayerId) {
            authoritativeState.availableActions.mapNotNull { it.toCommand() }
        } else {
            emptyList()
        }
        return redacted.copy(availableCommands = commands)
    }

    private companion object {
        /** Valor mínimo de Juego (31) para "tener juego" en la declaración. */
        const val JUEGO_MIN = 31
    }
}
