package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import kotlin.random.Random

// Señas online (Fase 4.2): prob. de que una IA pase su seña al entrar a MUS, por si su
// pareja es humana (capitán que necesita la seña para el corte #20) o IA. Espejan
// GameViewModel.PENDING_GESTURE_PROB_* (offline): duplicados a propósito —el planificador
// host es código separado del ViewModel local (como scoreRoundOver), para no acoplar el
// motor a presentation. Mismos valores → misma sensación.
private const val MUS_PENDING_GESTURE_PROB_HUMAN_PARTNER = 0.95f
private const val MUS_PENDING_GESTURE_PROB_AI_PARTNER = 0.90f

class MusGameLogic constructor(
    private val random: Random,
    private val logger: GameLogger = GameLogger.NoOp
) {


    // Creates a standard 40-card Spanish deck.
    fun createDeck(): List<Card> {
        return Suit.values().flatMap { suit ->
            Rank.values().map { rank ->
                Card(suit, rank)
            }
        }
    }

    // Shuffles the deck randomly.
    fun shuffleDeck(deck: List<Card>): List<Card> {
        return deck.shuffled(random)
    }

    fun dealCards(players: List<Player>, deck: List<Card>, manoId: String): Pair<List<Player>, List<Card>> {
        var tempDeck = deck
        val orderedPlayers = getTurnOrderedPlayers(players, manoId)
        val hands = mutableMapOf<String, List<Card>>()

        orderedPlayers.forEach {
            val hand = tempDeck.take(4)
            tempDeck = tempDeck.drop(4)
            hands[it.id] = hand
        }

        val updatedPlayers = players.map { it.copy(hand = hands[it.id] ?: emptyList()) }
        return Pair(updatedPlayers, tempDeck)
    }


    /**
     * Gets the highest card from a hand for the "Grande" lance.
     * In Mus, the value hierarchy is: Rey, Caballo, Sota, Siete, Seis... As.
     */
    fun getHighestCard(hand: List<Card>): Card? {
        // We sort the hand to find the best card.
        // The sorting is reversed because higher value means better for "Grande".
        return hand.sortedWith(compareByDescending { it.rank.value }).firstOrNull()
    }

    /**
     * Gets the lowest card from a hand for the "Chica" lance.
     * The value hierarchy is: As, Dos, Tres... Rey.
     */
    fun getLowestCard(hand: List<Card>): Card? {
        // Here, a standard sort works because lower value is better for "Chica".
        return hand.sortedWith(compareBy { it.rank.value }).firstOrNull()
    }

    /**
     * Determines the winner of the "Grande" lance among all players.
     * It considers the player order ("mano") to break ties.
     * The player who is "mano" (first in the list) wins any ties.
     */
    fun getGrandeWinner(gameState: GameState): Player? =
        // Grande: mejor = carta más ALTA; carta ausente cuenta como 0.
        lanceWinnerByCards(gameState, descending = true, absentCardValue = 0)

    /**
     * Determines the winner of the "Chica" lance among all players.
     * It considers the player order ("mano") to break ties.
     */
    fun getChicaWinner(gameState: GameState): Player? =
        // Chica: mejor = carta más BAJA; carta ausente cuenta como 13.
        lanceWinnerByCards(gameState, descending = false, absentCardValue = 13)

    /**
     * Núcleo común de Grande/Chica: compara mano a mano carta por carta en el
     * orden de turno (desde la mano), de modo que el empate exacto lo gana el
     * jugador que está antes en el turno ("mano" rompe empates). `descending`
     * = true para Grande (gana la más alta), false para Chica (la más baja);
     * `absentCardValue` es el valor de una posición sin carta (0 / 13).
     */
    private fun lanceWinnerByCards(
        gameState: GameState,
        descending: Boolean,
        absentCardValue: Int
    ): Player? {
        val orderedPlayers = getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        return orderedPlayers.reduceOrNull { winner, challenger ->
            val winnerHand = if (descending) winner.hand.sortedByDescending { it.rank.value }
            else winner.hand.sortedBy { it.rank.value }
            val challengerHand = if (descending) challenger.hand.sortedByDescending { it.rank.value }
            else challenger.hand.sortedBy { it.rank.value }

            for (i in 0..3) {
                val winnerCardValue = winnerHand.getOrNull(i)?.rank?.value ?: absentCardValue
                val challengerCardValue = challengerHand.getOrNull(i)?.rank?.value ?: absentCardValue

                val challengerBetter = if (descending) challengerCardValue > winnerCardValue
                else challengerCardValue < winnerCardValue
                val winnerBetter = if (descending) winnerCardValue > challengerCardValue
                else winnerCardValue < challengerCardValue

                if (challengerBetter) return@reduceOrNull challenger
                if (winnerBetter) return@reduceOrNull winner
            }
            // Empate exacto: gana el que está antes en el turno.
            winner
        }
    }

    /**
     * Seña canónica que comunica [hand] (la que el jugador "pasa"), o null si la mano
     * no es señalizable. VERAZ: se deriva de la mano (no se puede mentir). Extraída del
     * ViewModel (Fase 4 de multijugador) para que el host autoritativo la calcule por
     * asiento igual que el modo local. Treses cuentan como Reyes y Doses como Ases.
     */
    fun determineGesture(hand: List<Card>): GestureKind? {
        if (hand.isEmpty()) return null
        val reyesCount = hand.count { it.rank == Rank.REY || it.rank == Rank.TRES }
        val asesCount = hand.count { it.rank == Rank.AS || it.rank == Rank.DOS }
        val paresPlay = getHandPares(hand)
        val juegoValue = getHandJuegoValue(hand)

        // Prioridad: duples > 31 > medias > pares > ciega.
        return when {
            // #23: "duples altos" = par alto de Reyes (comunica "2 reyes"); resto = bajos.
            paresPlay is ParesPlay.Duples ->
                if (reyesCount >= 2) GestureKind.DUPLES_ALTOS else GestureKind.DUPLES_BAJOS
            juegoValue == 31 -> GestureKind.JUEGO_31
            reyesCount >= 3 -> GestureKind.REYES_3
            asesCount >= 3 -> GestureKind.ASES_3
            reyesCount == 2 -> GestureKind.REYES_2
            asesCount == 2 -> GestureKind.ASES_2
            paresPlay is ParesPlay.NoPares && juegoValue < 31 -> GestureKind.CIEGA
            else -> null
        }
    }

    /**
     * Señas online (Fase 4.2): pre-decide qué IA pasan seña al entrar a MUS. Espejo
     * host-side de `GameViewModel.onEnterMusPhase`, pero SIN `localSeatId` (online hay
     * hasta 2 humanos): el "compañero humano" se decide POR ASIENTO (`partner.isAi ==
     * false`), no contra un único humano local. Para cada IA CON pareja y mano
     * señalizable, tira [rng]: [MUS_PENDING_GESTURE_PROB_HUMAN_PARTNER] si su pareja es
     * humana (el capitán necesita la seña para el corte #20), si no la de IA-IA. Vacío
     * en Mus corrido (#17: sin señas hasta el 1er corte). Puro y determinista por [rng].
     */
    fun planAiGestures(state: GameState, rng: Random): Map<String, GestureKind> {
        if (state.musCorrido) return emptyMap()
        return state.players.filter { ai ->
            ai.isAi && state.players.any { p -> p.id != ai.id && p.team == ai.team }
        }.mapNotNull { ai ->
            val gesture = determineGesture(ai.hand) ?: return@mapNotNull null
            val partnerIsHuman = state.players.any { p -> p.id != ai.id && p.team == ai.team && !p.isAi }
            val prob = if (partnerIsHuman) {
                MUS_PENDING_GESTURE_PROB_HUMAN_PARTNER
            } else {
                MUS_PENDING_GESTURE_PROB_AI_PARTNER
            }
            if (rng.nextFloat() < prob) ai.id to gesture else null
        }.toMap()
    }

    /**
     * Analyzes a hand and returns the corresponding ParesPlay.
     */
    fun getHandPares(hand: List<Card>): ParesPlay {

        fun getPairingRank(card: Card): Rank {
            return when (card.rank) {
                Rank.TRES -> Rank.REY // Los treses cuentan como reyes
                Rank.DOS -> Rank.AS   // Los doses cuentan como ases
                else -> card.rank
            }
        }
        // Group cards by their rank and count them.
        // Example: {REY=3, AS=1}
        val counts = hand.groupingBy { getPairingRank(it) }.eachCount()

        // Filter for ranks that appear 2 or more times.
        val pairsOrBetter = counts.filter { it.value >= 2 }

        if (pairsOrBetter.isEmpty()) {
            return ParesPlay.NoPares
        }

        val fourOfAKind = pairsOrBetter.filter { it.value == 4 }
        if (fourOfAKind.isNotEmpty()) {
            // Esto se considera Duples de la pareja más alta y la más baja de esa figura
            val rank = fourOfAKind.keys.first()
            return ParesPlay.Duples(rank, rank)
        }

        // Check for Medias (three of a kind)
        val medias = pairsOrBetter.filter { it.value == 3 }
        if (medias.isNotEmpty()) {
            // A player with Medias can't have Duples, so this is the best play.
            return ParesPlay.Medias(medias.keys.first())
        }

        // At this point, we know there are no Medias, so we check for Duples.
        if (pairsOrBetter.size == 2) {
            val ranks = pairsOrBetter.keys.sortedByDescending { it.value }
            return ParesPlay.Duples(ranks[0], ranks[1])
        }

        // If none of the above, it must be a single pair.
        return ParesPlay.Pares(pairsOrBetter.keys.first())
    }

    /**
     * Determines the winner of the "Pares" lance among all players.
     */
    fun getParesWinner(gameState: GameState): Player? {
        val orderedPlayers = getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val playersWithPares = orderedPlayers.map { it to getHandPares(it.hand) }
            .filter { it.second.strength > 0 }

        if (playersWithPares.isEmpty()) return null

        return playersWithPares.reduce { winner, challenger ->
            val winnerPlay = winner.second
            val challengerPlay = challenger.second

            if (challengerPlay.strength > winnerPlay.strength) return@reduce challenger
            if (challengerPlay.strength < winnerPlay.strength) return@reduce winner

            // Si la fuerza es la misma, compara los rangos
            val challengerIsBetter = when (winnerPlay) {
                is ParesPlay.Duples -> {
                    val c = challengerPlay as ParesPlay.Duples
                    if (c.highPair.value > winnerPlay.highPair.value) true
                    else c.highPair.value == winnerPlay.highPair.value && c.lowPair.value > winnerPlay.lowPair.value
                }
                is ParesPlay.Medias -> (challengerPlay as ParesPlay.Medias).rank.value > winnerPlay.rank.value
                is ParesPlay.Pares -> (challengerPlay as ParesPlay.Pares).rank.value > winnerPlay.rank.value
                else -> false
            }

            if (challengerIsBetter) challenger else winner
        }.first
    }

    /**
     * Calculates the total value of a hand for the "Juego" lance.
     * Figures (Sota, Caballo, Rey) are worth 10. Other cards are worth their face value.
     */
    internal fun getHandJuegoValue(hand: List<Card>): Int {
        return hand.sumOf { card ->
            when (card.rank) {
                Rank.SOTA, Rank.CABALLO, Rank.REY -> 10
                Rank.TRES -> 10
                Rank.DOS -> 1
                else -> card.rank.value
            }
        }
    }

    /**
     * Determines the winner of the "Juego" or "Punto" lance.
     */
    fun getJuegoWinner(gameState: GameState): Player? {
        val orderedPlayers = getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        val playerScores = orderedPlayers.map { it to getHandJuegoValue(it.hand) }
        val (withJuego, atPunto) = playerScores.partition { it.second >= 31 }

        val winnerPair = if (withJuego.isNotEmpty()) {
            withJuego.sortedWith(
                compareByDescending<Pair<Player, Int>> { (_, score) -> score == 31 }
                    .thenByDescending { (_, score) -> score == 32 }
                    .thenByDescending { (_, score) -> score }
            ).firstOrNull()
        } else {
            atPunto.maxByOrNull { it.second }
        }
        return winnerPair?.first
    }

    fun processAction(currentState: GameState, action: GameAction, playerId: String): GameState {
        // ---- VALIDACIÓN INICIAL ----
        val player = currentState.players.find { it.id == playerId } ?: return currentState
        if (currentState.currentTurnPlayerId != playerId) return currentState
        val isActionAllowed = currentState.availableActions.any { it::class == action::class }
        if (!isActionAllowed && action !is GameAction.ConfirmDiscard) {
            // Excepción: Permitimos "Tengo" y "No Tengo" aunque no estén en la lista,
            // ya que son acciones automáticas que el sistema debe procesar.
            if (action !is GameAction.Tengo && action !is GameAction.NoTengo) {
                return currentState
            }
        }

        // Validaciones específicas de lances...
        when (currentState.gamePhase) {
            GamePhase.PARES -> if (getHandPares(player.hand).strength == 0) return handlePaso(currentState, playerId)
            GamePhase.JUEGO -> if (!currentState.isPuntoPhase && getHandJuegoValue(player.hand) < 31) return handlePaso(currentState, playerId)
            else -> {}
        }

        // ---- CÁLCULO DEL SIGUIENTE ESTADO ----
        val nextState = when (action) {
            is GameAction.Mus -> handleMus(currentState, playerId)
            is GameAction.NoMus -> handleNoMus(currentState, playerId)
            is GameAction.ConfirmDiscard -> handleDiscard(currentState, playerId)
            is GameAction.Paso -> handlePaso(currentState, playerId)
            is GameAction.Envido -> handleEnvido(currentState, playerId, action.amount)
            is GameAction.Quiero -> handleQuiero(currentState)
            is GameAction.NoQuiero -> handleNoQuiero(currentState, playerId)
            is GameAction.Órdago -> handleOrdago(currentState, playerId)
            else -> currentState
        }

        if (nextState == currentState) return currentState

        // ---- FINALIZACIÓN Y GESTIÓN DE ANUNCIOS ----
        // `currentLanceActions` es la ÚNICA fuente de verdad de los anuncios:
        // mapa jugador→última acción del lance. Mutado solo de forma SÍNCRONA
        // (aquí, acumulando por jugador; y el ViewModel lo vacía de golpe en
        // la frontera de lance, sin campo transient ni filterNot parcial), de
        // modo que cada `ActionAnnouncement` observe un único valor monótono
        // y no haya carrera de timing entre capas (#27).
        //
        // PERSISTENCIA dentro del lance: la acción de cada jugador queda
        // visible hasta que el lance se resuelve (el jugador llega a su turno
        // sabiendo a qué responde). NO se reduce al cerrar lance: se conservan
        // TODAS las acciones del lance cerrado durante el beat de ritmo del
        // ViewModel (lance resuelto legible) y este las vacía juntas antes de
        // la primera acción del lance nuevo.
        //
        // Un Envido sobre un envite ya existente es una SUBIDA: guardamos el
        // importe previo (no nulo ⇒ subida) para que el anuncio diga "N más"
        // en vez de "Envido N" (#18).
        val newActionInfo = if (action is GameAction.Envido && currentState.currentBet != null) {
            LastActionInfo(playerId, action, amount = currentState.currentBet.amount)
        } else {
            LastActionInfo(playerId, action)
        }

        val updatedLanceActions = currentState.currentLanceActions + (playerId to newActionInfo)

        return nextState.copy(
            lastAction = newActionInfo,
            actionLog = (nextState.actionLog + newActionInfo).takeLast(4),
            currentLanceActions = updatedLanceActions
        )
    }

    //encapsula la puntuación y la comprobación de fin de partida.
    private fun scoreAndCheckForGameOver(roundEndState: GameState): GameState {
        val scoredState = scoreRound(roundEndState)

        val scoreToWin = 40 // O el valor que definas
        val teamAScore = scoredState.score["teamA"] ?: 0
        val teamBScore = scoredState.score["teamB"] ?: 0
        val winner = if (teamAScore >= scoreToWin) "teamA" else if (teamBScore >= scoreToWin) "teamB" else null

        return if (winner != null) {
            scoredState.copy(
                gamePhase = GamePhase.GAME_OVER,
                winningTeam = winner,
                availableActions = listOf(GameAction.NewGame),
                revealAllHands = true
            )
        } else {
            // La ronda terminó, pero la partida sigue.
            // Prepara el estado para la siguiente ronda.
            scoredState.copy(
                gamePhase = GamePhase.ROUND_OVER,
                availableActions = listOf(GameAction.Continue),
                revealAllHands = true
            )
        }
    }

    /**
     * Oponentes aptos a partir del que apuesta, en el orden de turno correcto
     * (sentido horario, fórmula -i). Lista usada por envido y órdago para fijar
     * `respondingPlayerId`/`playersPendingResponse`. NO usar `findNextOpponent`
     * (código muerto consciente, ver KNOWN_ISSUES): esta es la ruta viva.
     */
    private fun orderedEligibleOpponentIds(
        currentState: GameState,
        playerId: String,
        allEligibleOpponents: List<Player>
    ): List<String> {
        val bettingPlayerIndex = currentState.players.indexOfFirst { it.id == playerId }
        val result = mutableListOf<String>()
        if (bettingPlayerIndex != -1) {
            for (i in 1..currentState.players.size) {
                val nextPlayer = currentState.players[(bettingPlayerIndex - i + currentState.players.size) % currentState.players.size]
                if (nextPlayer in allEligibleOpponents) {
                    result.add(nextPlayer.id)
                }
            }
        }
        return result
    }

    private fun handleEnvido(currentState: GameState, playerId: String, amount: Int): GameState {
        val bettingPlayer = currentState.players.find { it.id == playerId } ?: return currentState
        val opponentTeam = if (bettingPlayer.team == "teamA") "teamB" else "teamA"

        val previousBetAmount = currentState.currentBet?.amount ?: 0
        val pointsIfRejected = if (previousBetAmount == 0) 1 else previousBetAmount
        // 1. Obtenemos la lista de TODOS los jugadores aptos para este lance
        val eligiblePlayers = getEligiblePlayersForLance(currentState)

        // 2. Filtramos solo a los oponentes que son aptos para hablar
        val allEligibleOpponents = eligiblePlayers.filter { it.team == opponentTeam }

        // Si no hay oponentes aptos, la acción no es válida
        if (allEligibleOpponents.isEmpty()) {
            return currentState
        }

        val orderedEligibleOpponents =
            orderedEligibleOpponentIds(currentState, playerId, allEligibleOpponents)

        // Si por alguna razón no se encuentran oponentes, devolvemos el estado actual
        if (orderedEligibleOpponents.isEmpty()) {
            return currentState
        }

        val totalAmount = (currentState.currentBet?.amount ?: 0) + amount
        val newBet = BetInfo(
            amount = totalAmount,
            bettingPlayerId = playerId,
            respondingPlayerId = orderedEligibleOpponents.first(),
            pointsIfRejected = pointsIfRejected
        )
        // #16 R4.e: actualiza el envido MÁXIMO que este jugador ha lanzado en
        // esta ronda. AILogic.decideResponse usa este histórico para endurecer
        // el umbral de aceptación si el rival ha apostado FUERTE antes.
        val newMaxBets = currentState.playerMaxBetThisRound +
            (playerId to maxOf(currentState.playerMaxBetThisRound[playerId] ?: 0, amount))

        return currentState.copy(
            currentBet = newBet,
            currentTurnPlayerId = orderedEligibleOpponents.first(),
            betInitiatorTeam = bettingPlayer.team,
            playersPendingResponse = orderedEligibleOpponents,
            availableActions = listOf(GameAction.Quiero, GameAction.NoQuiero, GameAction.Envido(2), GameAction.Órdago),
            playerMaxBetThisRound = newMaxBets,
            // Cuando alguien sube la apuesta, los que habían pasado ("Paso") pueden volver a hablar,
            // pero los que dijeron "No Quiero" no. Por eso, reiniciamos la lista de "pasos".
            playersWhoPassed = currentState.playersWhoPassed
        )
    }


    private fun handleQuiero(currentState: GameState): GameState {
        if (currentState.currentBet?.isOrdago == true) {
            return resolveOrdagoShowdown(currentState)
        }

        val bet = currentState.currentBet ?: return currentState

        // NO se suman puntos aquí. Solo se guarda la cantidad acordada.
        val resolvedState = currentState.copy(
            betInitiatorTeam = null,
            playersPendingResponse = emptyList(),
            agreedBets = currentState.agreedBets + (currentState.gamePhase to bet.amount)
        )
        
        return endLanceAndAdvance(resolvedState) { this }
    }

    private fun handleNoQuiero(currentState: GameState, playerId: String): GameState {
        if (currentState.currentBet == null || playerId !in currentState.playersPendingResponse) {
            return currentState
        }

        val remainingResponders = currentState.playersPendingResponse.filter { it != playerId }

        // Si ya no quedan miembros del equipo por responder, el rechazo es definitivo
        if (remainingResponders.isEmpty()) {
            val bet = currentState.currentBet
            // Buscamos al jugador que lanzó el envite para saber a qué equipo darle los puntos
            val bettingPlayer = currentState.players.find { it.id == bet.bettingPlayerId } ?: return currentState

            // Calculamos los puntos: los que ya estaban acordados + 1 por el rechazo
            val pointsWon = bet.pointsIfRejected
            val currentScore = currentState.score[bettingPlayer.team] ?: 0
            // Actualizamos el marcador EN ESTE MOMENTO
            val newScore = currentState.score + (bettingPlayer.team to currentScore + pointsWon)
            val reason = "${currentState.gamePhase.name} No Querida"
            val scoreEvent = ScoreEventInfo(bettingPlayer.team, ScoreDetail(reason, pointsWon))

            logger.d("MusVistoTest", "APUESTA RECHAZADA. El equipo ${bettingPlayer.team} gana $pointsWon punto(s) al instante.")

            // Preparamos el estado para pasar al siguiente lance
            val resolvedState = currentState.copy(
                betInitiatorTeam = null,
                playersPendingResponse = emptyList(),
                score = newScore, // <-- Guardamos el nuevo marcador,
                scoreEvents = currentState.scoreEvents + scoreEvent // <-- y añadimos el evento de puntuación
            )
            // Y avanzamos al siguiente lance
            return endLanceAndAdvance(resolvedState) { this }
        } else {
            // Si aún queda un compañero por hablar, el turno pasa a él y no se suman puntos todavía
            return currentState.copy(
                playersPendingResponse = remainingResponders,
                currentTurnPlayerId = remainingResponders.first()
            )
        }
    }


    private fun handleMus(currentState: GameState, playerId: String): GameState {
        val newState = currentState.copy(
            playersWhoPassed = currentState.playersWhoPassed + playerId
        )

        // If everyone has said "Mus", we go to the discard phase
        return if (newState.playersWhoPassed.size == newState.players.size) {
            newState.copy(
                gamePhase = GamePhase.DISCARD,
                availableActions = listOf(GameAction.ConfirmDiscard),
                playersWhoPassed = emptySet(),
                currentTurnPlayerId = newState.manoPlayerId,
                isNewLance = true,
                currentLanceActions = emptyMap(),
                knownGestures = emptyMap(),
                // Cada ciclo de descarte arranca limpio: el badge refleja
                // SOLO este ciclo (fix multi-ronda: la 2ª ronda no arrastra
                // los conteos de la 1ª).
                discardCounts = emptyMap()
            )
        } else {
            // If not, it's the next player's turn to decide on Mus
            setNextPlayerTurn(newState)
        }
    }

    private fun handleNoMus(currentState: GameState, playerId: String): GameState {
        // Mus corrido (#17): el que corta el mus se convierte en mano (postre =
        // su izquierda, implícito por el orden de turno desde manoPlayerId).
        // Termina el modo inicial; a partir de aquí, juego normal.
        val newManoId = if (currentState.musCorrido) playerId else currentState.manoPlayerId
        return currentState.copy(
            gamePhase = GamePhase.GRANDE,
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago),
            playersWhoPassed = emptySet(),
            // Cerrado el Mus, los descartes ya no son relevantes: el badge
            // del avatar desaparece al entrar en GRANDE (revisión de #27:
            // antes vivía toda la ronda; el usuario lo quiere acotado a la
            // fase de Mus/descarte).
            discardCounts = emptyMap(),
            currentBet = null,
            manoPlayerId = newManoId,
            currentTurnPlayerId = newManoId,
            isNewLance = true,
            currentLanceActions = emptyMap(),
            noMusPlayer = playerId,
            // El plan de señas era solo para el Mus que acaba de cerrarse;
            // los lances de envite ya no lo usan.
            pendingGestures = emptyMap(),
            musCorrido = false
        )
    }

    private fun handlePaso(currentState: GameState, playerId: String): GameState {
        // El "Paso" solo se gestiona si NO hay una apuesta activa.
        // El bloque que manejaba el caso de una apuesta activa se ha eliminado
        // porque es un estado de juego que no debería ser posible.

        val newPassedSet = currentState.playersWhoPassed + playerId

        // Usamos nuestra función auxiliar para obtener los jugadores aptos para el lance
        val eligiblePlayers = getEligiblePlayersForLance(currentState)

        // Si todos los jugadores aptos ya han pasado, el lance termina sin apuestas.
        if (newPassedSet.containsAll(eligiblePlayers.map { it.id })) {
            return endLanceAndAdvance(currentState) { this }
        }

        // Si no, pasa el turno al siguiente jugador apto.
        return setNextPlayerTurn(currentState).copy(playersWhoPassed = newPassedSet)
    }


    private fun handleOrdago(currentState: GameState, playerId: String): GameState {
        val bettingPlayer = currentState.players.find { it.id == playerId } ?: return currentState
        val opponentTeam = if (bettingPlayer.team == "teamA") "teamB" else "teamA"
        val previousBetAmount = currentState.currentBet?.amount ?: 0
        val pointsIfRejected = if (previousBetAmount == 0) 1 else previousBetAmount

        // 1. Obtenemos la lista de TODOS los jugadores aptos para este lance
        val eligiblePlayers = getEligiblePlayersForLance(currentState)

        // 2. Filtramos solo a los oponentes que son aptos para hablar
        val allEligibleOpponents = eligiblePlayers.filter { it.team == opponentTeam }

        // Si no hay oponentes aptos, la acción no es válida
        if (allEligibleOpponents.isEmpty()) {
            return currentState
        }

        val orderedEligibleOpponents =
            orderedEligibleOpponentIds(currentState, playerId, allEligibleOpponents)

        // Si por alguna razón no se encuentran oponentes, devolvemos el estado actual
        if (orderedEligibleOpponents.isEmpty()) {
            return currentState
        }

        // El órdago se juega los 40 puntos del juego.
        val newBet = BetInfo(
            amount = 40,
            bettingPlayerId = playerId,
            respondingPlayerId = orderedEligibleOpponents.first(),
            isOrdago = true, // Marcamos la apuesta como un órdago
            pointsIfRejected = pointsIfRejected
        )
        // #16 R4.e: el órdago cuenta como la apuesta MÁXIMA posible (40) en
        // playerMaxBetThisRound — un futuro órdago RIVAL al que respondamos
        // se endurece sabiendo que este jugador es agresivo.
        val newMaxBets = currentState.playerMaxBetThisRound + (playerId to 40)

        return currentState.copy(
            currentBet = newBet,
            currentTurnPlayerId = orderedEligibleOpponents.first(),
            betInitiatorTeam = bettingPlayer.team,
            playersPendingResponse = orderedEligibleOpponents,
            playerMaxBetThisRound = newMaxBets,
            // Al órdago solo se puede responder con "Quiero" o "No Quiero".
            availableActions = listOf(GameAction.Quiero, GameAction.NoQuiero),
            playersWhoPassed = emptySet()
        )
    }

    private fun resolveOrdagoShowdown(currentState: GameState): GameState {
        logger.d("MusVistoTest", "¡ÓRDAGO ACEPTADO! Resolviendo la partida...")

        val lanceWinner: Player? = when (currentState.gamePhase) {
            GamePhase.GRANDE -> getGrandeWinner(currentState)
            GamePhase.CHICA -> getChicaWinner(currentState)
            GamePhase.PARES -> getParesWinner(currentState)
            GamePhase.JUEGO -> getJuegoWinner(currentState)
            else -> null
        }

        val winner = lanceWinner ?: currentState.players.find { it.id == currentState.currentBet?.bettingPlayerId }
        if (winner == null) return currentState.copy(gamePhase = GamePhase.GAME_OVER) // Salida de seguridad

        // Guardamos la información del órdago para la UI
        val ordagoInfo = OrdagoInfo(winner.id, currentState.gamePhase)

        // Asignamos los 40 puntos y terminamos la partida
        val finalScore = currentState.score.toMutableMap()
        finalScore[winner.team] = 40

        return currentState.copy(
            score = finalScore,
            gamePhase = GamePhase.GAME_OVER,
            winningTeam = winner.team,
            ordagoInfo = ordagoInfo, // <-- Guardamos el resultado
            availableActions = listOf(GameAction.NewGame),
            revealAllHands = true
        )
    }

    /**
     * Cierra el lance actual y prepara el siguiente. Orquesta tres
     * responsabilidades, ahora separadas en funciones nombradas:
     * 1) registrar el resultado del lance, 2) calcular la fase siguiente,
     * 3) resetear turno/apuesta/acciones para el lance nuevo.
     */
    private fun endLanceAndAdvance(currentState: GameState, updates: GameState.() -> GameState): GameState {
        val withResult = recordLanceResult(currentState.updates())
        val nextPhase = advanceToNextPhase(withResult.gamePhase)
        return resetForNextLance(withResult, nextPhase)
    }

    /** Añade a `roundHistory` el resultado del lance que acaba de cerrarse. */
    private fun recordLanceResult(state: GameState): GameState {
        val lanceResult = when {
            state.currentBet != null && state.agreedBets.containsKey(state.gamePhase) ->
                LanceResult(state.gamePhase, "Querido", state.currentBet.amount)
            state.currentBet != null -> {
                // En la no querida el ganador se conoce al instante: el equipo que
                // envidó se lleva pointsIfRejected (mismo cálculo que handleNoQuiero).
                val winningTeam = state.players.find { it.id == state.currentBet.bettingPlayerId }?.team
                LanceResult(state.gamePhase, "No Querido", state.currentBet.pointsIfRejected, winningTeam)
            }
            else -> LanceResult(state.gamePhase, "Paso")
        }
        return state.copy(roundHistory = state.roundHistory + lanceResult)
    }

    /** Resetea turno (a la mano), apuesta y acciones disponibles para `nextPhase`. */
    private fun resetForNextLance(state: GameState, nextPhase: GamePhase): GameState =
        state.copy(
            gamePhase = nextPhase,
            currentTurnPlayerId = state.manoPlayerId,
            playersWhoPassed = emptySet(),
            currentBet = null,
            isNewLance = true,
            actionLog = emptyList(),
            currentLanceActions = emptyMap(),
            availableActions = if (nextPhase == GamePhase.PARES_CHECK || nextPhase == GamePhase.JUEGO_CHECK) emptyList()
            else listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )


    private fun advanceToNextPhase(currentPhase: GamePhase): GamePhase {
        return when (currentPhase) {
            GamePhase.GRANDE -> GamePhase.CHICA
            GamePhase.CHICA -> GamePhase.PARES_CHECK
            GamePhase.PARES -> GamePhase.JUEGO_CHECK
            GamePhase.JUEGO -> GamePhase.ROUND_OVER
            else -> currentPhase // Should not happen
        }
    }

    private fun setNextPlayerTurn(currentState: GameState): GameState {
        val players = currentState.players
        val currentPlayerId = currentState.currentTurnPlayerId ?: return currentState
        val currentIndex = players.indexOfFirst { it.id == currentPlayerId }
        if (currentIndex == -1) return currentState // Medida de seguridad

        val eligiblePlayerIds = if (currentState.playersInLance.isNotEmpty()) {
            currentState.playersInLance
        } else {
            // Si no es un lance restringido (como Grande/Chica), todos pueden jugar.
            players.map { it.id }.toSet()
        }

        // 2. Busca al siguiente jugador en el orden de turno
        for (i in 1..players.size) {
            val nextIndex = (currentIndex - i + players.size) % players.size
            val nextPlayer = players[nextIndex]

            // 3. ...que sea ELEGIBLE para este lance Y que no haya pasado ya.
            if (nextPlayer.id in eligiblePlayerIds && nextPlayer.id !in currentState.playersWhoPassed) {
                return currentState.copy(currentTurnPlayerId = nextPlayer.id)
            }
        }
        // --- FIN DE LA CORRECCIÓN ---

        // Si el bucle termina, significa que todos los jugadores elegibles han actuado.
        return currentState
    }

    // Reemplaza la función antigua con esta
    private fun handleDiscard(currentState: GameState, playerId: String): GameState {
        val player = currentState.players.find { it.id == playerId } ?: return currentState
        val cardsToDiscard = currentState.selectedCardsForDiscard
        var event: GameEvent? = null

        if (currentState.gamePhase == GamePhase.DISCARD && currentState.selectedCardsForDiscard.isEmpty()) {
            logger.e("MusGameLogic", "Error: Intento de descarte vacío en fase de Mus por el jugador $playerId.")
            return currentState // Devuelve el estado sin cambios.
        }

        var deck = currentState.deck
        var discardPile = currentState.discardPile
        val cardsNeeded = cardsToDiscard.size
        var newCards: List<Card>

        if (deck.size < cardsNeeded) {
            logger.d("MusVistoTest", "Deck empty! Shuffling discard pile of ${discardPile.size} cards.")
            event = GameEvent.DISCARD_PILE_SHUFFLED
            val fromOldDeck = deck
            deck = discardPile.shuffled(random)
            discardPile = emptyList()
            val neededFromNewDeck = cardsNeeded - fromOldDeck.size
            newCards = fromOldDeck + deck.take(neededFromNewDeck)
            deck = deck.drop(neededFromNewDeck)
        } else {
            newCards = deck.take(cardsNeeded)
            deck = deck.drop(cardsNeeded)
        }
        val cardCount = cardsToDiscard.size
        val customActionText ="Descarta $cardCount"

        val customLogAction = GameAction.LogAction(customActionText)

        val newActionInfo = LastActionInfo(playerId, customLogAction)
        val newLog = (currentState.actionLog + newActionInfo).takeLast(4)

        discardPile += cardsToDiscard

        val newHand = player.hand.filterNot { it in cardsToDiscard } + newCards

        val updatedPlayer = player.copy(hand = newHand)
        val updatedPlayers = currentState.players.map { if (it.id == playerId) updatedPlayer else it }

        val newPassedSet = currentState.playersWhoPassed + playerId
        val newDiscardCounts = currentState.discardCounts + (playerId to cardsToDiscard.size)

        if (newPassedSet.size == updatedPlayers.size) {
            // Mus corrido (#17): si los 4 piden mus y descartan, el dador rota
            // a la derecha → en el nuevo mus empieza a decidir el SIGUIENTE
            // jugador (siguiente en el orden de turno). Fuera de mus corrido,
            // se reabre el mus con la misma mano. El "siguiente" usa la misma
            // dirección que setNextPlayerTurn (índice decreciente).
            val nextStartId = if (currentState.musCorrido) {
                val manoIdx = updatedPlayers.indexOfFirst { it.id == currentState.manoPlayerId }
                if (manoIdx >= 0)
                    updatedPlayers[(manoIdx - 1 + updatedPlayers.size) % updatedPlayers.size].id
                else currentState.manoPlayerId
            } else currentState.manoPlayerId
            return currentState.copy(
                players = updatedPlayers,
                deck = deck,
                discardPile = discardPile,
                gamePhase = GamePhase.MUS,
                availableActions = listOf(GameAction.Mus, GameAction.NoMus),
                selectedCardsForDiscard = emptySet(),
                playersWhoPassed = emptySet(),
                discardCounts = newDiscardCounts,
                manoPlayerId = nextStartId,
                currentTurnPlayerId = nextStartId,
                actionLog = newLog,
                // #37: un ciclo Mus+descarte más sin cortar → fatiga de Mus de
                // la IA (AILogic.decideMus baja su umbral de corte por este +1).
                musRoundCount = currentState.musRoundCount + 1
            )
        }

        // 1. Creamos un estado intermedio con TODA la información actualizada,
        //    incluyendo quién acaba de descartar (newPassedSet).
        val intermediateState = currentState.copy(
            players = updatedPlayers,
            deck = deck,
            discardPile = discardPile,
            selectedCardsForDiscard = emptySet(),
            playersWhoPassed = newPassedSet,
            discardCounts = newDiscardCounts,
            event = event,
            actionLog = newLog
        )

        // 2. AHORA, llamamos a la nueva función de turno sobre este estado correcto.
        return setNextPlayerTurn(intermediateState)
    }

    private fun findNextOpponent(currentState: GameState, fromPlayer: Player): Player? {
        val startIndex = currentState.players.indexOf(fromPlayer)
        // We check the next 3 players in order
        for (i in 1..3) {
            val nextPlayer = currentState.players[(startIndex + i) % 4]
            if (nextPlayer.team != fromPlayer.team) {
                return nextPlayer
            }
        }
        return null // Should not happen in a 2v2 game
    }


    fun scoreRound(currentState: GameState): GameState {
        val teamADetails = mutableListOf<ScoreDetail>()
        val teamBDetails = mutableListOf<ScoreDetail>()
        val historyMap = currentState.roundHistory.associateBy { it.lance }

        currentState.scoreEvents.forEach { event ->
            if (event.teamId == "teamA") {
                teamADetails.add(event.detail)
            } else {
                teamBDetails.add(event.detail)
            }
        }

        // 1. PUNTUACIÓN DE LANCES CON APUESTAS ACEPTADAS O PASADOS
        listOf(GamePhase.GRANDE, GamePhase.CHICA, GamePhase.JUEGO, GamePhase.PARES).forEach { lance ->
            val winner = when (lance) {
                GamePhase.GRANDE -> getGrandeWinner(currentState)
                GamePhase.CHICA -> getChicaWinner(currentState)
                GamePhase.JUEGO -> getJuegoWinner(currentState)
                GamePhase.PARES -> getParesWinner(currentState)
                else -> null
            }
            val result = historyMap[lance]

            if (winner != null && result != null) {
                val winningTeam = winner.team

                // Caso 1: La apuesta fue aceptada ("Querido")
                if (result.outcome == "Querido") {
                    val points = currentState.agreedBets[lance] ?: 0
                    if (points > 0) {
                        val detail = ScoreDetail("${lance.name} (Apuesta)", points)
                        if (winningTeam == "teamA") teamADetails.add(detail) else teamBDetails.add(detail)
                    }
                }
                // Caso 2: El lance fue pasado ("Paso" o "Skipped")
                else if (result.outcome == "Paso" || result.outcome == "Skipped") {
                    // El punto de bonificación por ganar al Punto se gestiona por separado
                    if (lance == GamePhase.GRANDE || lance == GamePhase.CHICA) {
                        val detail = ScoreDetail(lance.name, 1)
                        if (winningTeam == "teamA") teamADetails.add(detail) else teamBDetails.add(detail)
                    }
                }
                // NO HACEMOS NADA PARA "No Querido", porque ya se ha puntuado.
            }
        }

        // 2. PUNTUACIÓN POR VALOR DE LAS JUGADAS (PARES Y JUEGO/PUNTO)
        // La jugada (tantos de pares/juego/punto) se la anota el equipo que GANA
        // el lance. En una "No Querida" lo gana quien ENVIDÓ —no el de mejor
        // jugada en el showdown—, equipo registrado en LanceResult.winningTeam
        // (#30). En "Querido"/"Paso"/"Skipped" decide el showdown.
        fun jugadaWinningTeam(lance: GamePhase, showdownWinner: Player?): String? {
            val result = historyMap[lance]
            return if (result?.outcome == "No Querido") result.winningTeam
            else showdownWinner?.team
        }

        // Puntos por la jugada de Pares
        val paresTeam = jugadaWinningTeam(GamePhase.PARES, getParesWinner(currentState))
        if (paresTeam != null) {
            currentState.players.forEach { player ->
                if (player.team == paresTeam) {
                    val (reason, playPoints) = getHandPares(player.hand).let {
                        when (it) {
                            is ParesPlay.Duples -> "Duples (${player.name})" to 3
                            is ParesPlay.Medias -> "Medias (${player.name})" to 2
                            is ParesPlay.Pares -> "Pares (${player.name})" to 1
                            else -> "" to 0
                        }
                    }
                    if (playPoints > 0) {
                        if (player.team == "teamA") teamADetails.add(ScoreDetail(reason, playPoints)) else teamBDetails.add(ScoreDetail(reason, playPoints))
                    }
                }
            }
        }

        // Puntos por la jugada de Juego y bonificación de Punto
        val juegoTeam = jugadaWinningTeam(GamePhase.JUEGO, getJuegoWinner(currentState))
        if (juegoTeam != null) {
            if (currentState.isPuntoPhase) {
                val detail = ScoreDetail("Punto", 1)
                if (juegoTeam == "teamA") teamADetails.add(detail) else teamBDetails.add(detail)
            }
            currentState.players.forEach { player ->
                if (player.team == juegoTeam) {
                    val juegoValue = getHandJuegoValue(player.hand)
                    if (juegoValue >= 31) {
                        val reason = "Juego ${juegoValue} (${player.name})"
                        val playPoints = if (juegoValue == 31) 3 else 2
                        val detail = ScoreDetail(reason, playPoints)
                        if (player.team == "teamA") teamADetails.add(detail) else teamBDetails.add(detail)
                    }
                }
            }
        }

        val breakdown = ScoreBreakdown(teamADetails, teamBDetails)
        return currentState.copy(scoreBreakdown = breakdown)
    }

    internal fun getTurnOrderedPlayers(players: List<Player>, manoId: String): List<Player> {
        val manoIndex = players.indexOfFirst { it.id == manoId }
        if (manoIndex == -1) return players // Salida de seguridad

        // Esta es la lógica correcta para construir la lista en el orden de juego (antihorario)
        // a partir de una lista de asientos que está en orden horario.
        val orderedPlayers = mutableListOf<Player>()
        for (i in 0 until players.size) {
            val playerIndex = (manoIndex - i + players.size) % players.size
            orderedPlayers.add(players[playerIndex])
        }
        return orderedPlayers
    }

    // --- NUEVAS FUNCIONES DE AYUDA PARA PUNTUAR ---
    private fun scoreParesPlays(currentState: GameState): GameState {
        var tempScore = currentState.score.toMutableMap()
        currentState.players.forEach { player ->
            val play = getHandPares(player.hand)
            val points = when (play) {
                is ParesPlay.Duples -> 3
                is ParesPlay.Medias -> 2
                is ParesPlay.Pares -> 1
                else -> 0
            }
            if (points > 0) {
                tempScore[player.team] = (tempScore[player.team] ?: 0) + points
            }
        }
        return currentState.copy(score = tempScore)
    }
    private fun scoreJuegoPlays(currentState: GameState): GameState {
        var tempScore = currentState.score.toMutableMap()
        currentState.players.forEach { player ->
            val value = getHandJuegoValue(player.hand)
            if (value >= 31) {
                val points = if (value == 31) 3 else 2
                tempScore[player.team] = (tempScore[player.team] ?: 0) + points
            }
        }
        return currentState.copy(score = tempScore)
    }

    private fun getEligiblePlayersForLance(currentState: GameState): List<Player> {
        return currentState.players.filter { player ->
            when (currentState.gamePhase) {
                GamePhase.PARES -> getHandPares(player.hand).strength > 0
                GamePhase.JUEGO -> if (currentState.isPuntoPhase) true else getHandJuegoValue(player.hand) >= 31
                else -> true // Todos los jugadores son aptos para Grande y Chica
            }
        }
    }
    /**
     * Resuelve un *_CHECK (PARES/JUEGO) eligiendo entre tres desenlaces, ahora
     * en funciones nombradas (misma lógica y mismo orden de prioridad):
     * 1) nadie tiene Juego en JUEGO_CHECK -> ronda de Punto;
     * 2) un solo equipo con jugada -> se salta el lance;
     * 3) ambos equipos -> ronda de apuestas normal.
     */
    fun resolveDeclaration(currentState: GameState): GameState {
        val currentCheckPhase = currentState.gamePhase // Será PARES_CHECK o JUEGO_CHECK

        val playersWithPlay = currentState.players.filter {
            if (currentCheckPhase == GamePhase.PARES_CHECK) {
                getHandPares(it.hand).strength > 0
            } else { // JUEGO_CHECK
                getHandJuegoValue(it.hand) >= 31
            }
        }

        val teamsWithPlay = playersWithPlay.map { it.team }.distinct()

        return when {
            currentCheckPhase == GamePhase.JUEGO_CHECK && teamsWithPlay.isEmpty() ->
                resolveAsPunto(currentState)
            teamsWithPlay.size < 2 ->
                skipDeclarationLance(currentState, currentCheckPhase, teamsWithPlay)
            else ->
                beginDeclarationBetting(currentState, currentCheckPhase, playersWithPlay)
        }
    }

    /** CASO 1: JUEGO_CHECK sin nadie con Juego -> ronda de Punto (todos hablan). */
    private fun resolveAsPunto(currentState: GameState): GameState {
        logger.d("MusVistoTest", "JUEGO: Nadie tiene. Pasando a la ronda de PUNTO.")
        return currentState.copy(
            gamePhase = GamePhase.JUEGO, // La fase de apuestas sigue siendo "JUEGO"
            isPuntoPhase = true, // <-- PERO activamos la bandera de "Punto"
            playersInLance = currentState.players.map { it.id }.toSet(), // Todos juegan
            currentTurnPlayerId = currentState.manoPlayerId,
            playersWhoPassed = emptySet(),
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
    }

    /** CASO 2: solo un equipo con jugada -> registra "Skipped" y avanza de fase. */
    private fun skipDeclarationLance(
        currentState: GameState,
        currentCheckPhase: GamePhase,
        teamsWithPlay: List<String>
    ): GameState {
        val lancePhase = if (currentCheckPhase == GamePhase.PARES_CHECK) GamePhase.PARES else GamePhase.JUEGO
        logger.d("MusVistoTest", "${lancePhase.name}: Ronda de apuestas saltada (equipos con jugada: ${teamsWithPlay.size}).")

        val historyResult = LanceResult(lance = lancePhase, outcome = "Skipped")
        val stateWithHistory = currentState.copy(roundHistory = currentState.roundHistory + historyResult)

        val nextPhase = advanceToNextPhase(lancePhase)
        return stateWithHistory.copy(
            gamePhase = nextPhase,
            currentTurnPlayerId = stateWithHistory.manoPlayerId,
            isPuntoPhase = false, // Nos aseguramos de resetear la bandera de Punto
            playersWhoPassed = emptySet(),
            isNewLance = true,
            currentLanceActions = emptyMap(),
            availableActions = if (nextPhase == GamePhase.JUEGO_CHECK) emptyList()
            else listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
    }

    /** CASO 3: ambos equipos con jugada -> ronda de apuestas normal. */
    private fun beginDeclarationBetting(
        currentState: GameState,
        currentCheckPhase: GamePhase,
        playersWithPlay: List<Player>
    ): GameState {
        val bettingPhase = if (currentCheckPhase == GamePhase.PARES_CHECK) GamePhase.PARES else GamePhase.JUEGO

        val firstPlayerToBet = getTurnOrderedPlayers(currentState.players, currentState.manoPlayerId)
            .first { it in playersWithPlay }

        return currentState.copy(
            gamePhase = bettingPhase,
            isPuntoPhase = false, // Nos aseguramos de que no se está jugando a Punto
            playersInLance = playersWithPlay.map { it.id }.toSet(),
            currentTurnPlayerId = firstPlayerToBet.id,
            playersWhoPassed = emptySet(),
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
    }
}