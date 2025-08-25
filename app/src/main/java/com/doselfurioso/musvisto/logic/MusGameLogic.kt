package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.model.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class MusGameLogic @Inject constructor(private val random: javax.inject.Provider<Random>){


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
        return deck.shuffled(random.get())
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
    fun getGrandeWinner(gameState: GameState): Player? {
        val orderedPlayers = getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        return orderedPlayers.reduceOrNull { winner, challenger ->
            val winnerCard = winner.hand.maxByOrNull { it.rank.value }
            val challengerCard = challenger.hand.maxByOrNull { it.rank.value }
            if ((challengerCard?.rank?.value ?: 0) > (winnerCard?.rank?.value ?: 0)) challenger else winner
        }
    }

    /**
     * Determines the winner of the "Chica" lance among all players.
     * It considers the player order ("mano") to break ties.
     */
    fun getChicaWinner(gameState: GameState): Player? {
        val orderedPlayers = getTurnOrderedPlayers(gameState.players, gameState.manoPlayerId)
        return orderedPlayers.reduceOrNull { winner, challenger ->
            val winnerCard = winner.hand.minByOrNull { it.rank.value }
            val challengerCard = challenger.hand.minByOrNull { it.rank.value }
            if ((challengerCard?.rank?.value ?: 13) < (winnerCard?.rank?.value ?: 13)) challenger else winner
        }
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
        // --- VALIDACIÓN DE ACCIÓN ---
        val player = currentState.players.find { it.id == playerId } ?: return currentState

        // 1. No se puede actuar si no es tu turno
        if (currentState.currentTurnPlayerId != playerId) return currentState

        // 2. No se puede realizar una acción que no está disponible
        if (action !in currentState.availableActions) return currentState

        // 3. Lógica específica del lance
        when (currentState.gamePhase) {
            GamePhase.PARES -> if (getHandPares(player.hand).strength == 0) return handlePaso(currentState, playerId)
            GamePhase.JUEGO -> if (!currentState.isPuntoPhase && getHandJuegoValue(player.hand) < 31) return handlePaso(currentState, playerId)
            else -> {} // No special logic for other phases
        }

        // Si la acción es válida, la procesamos
        val nextState = when (action) {
            is GameAction.Mus -> handleMus(currentState, playerId)
            is GameAction.NoMus -> handleNoMus(currentState)
            is GameAction.ConfirmDiscard -> handleDiscard(currentState, playerId)
            is GameAction.Paso -> handlePaso(currentState, playerId)
            is GameAction.Envido -> handleEnvido(currentState, playerId, action.amount)
            is GameAction.Quiero -> handleQuiero(currentState)
            is GameAction.NoQuiero -> handleNoQuiero(currentState, playerId)
            is GameAction.Órdago -> handleOrdago(currentState, playerId)
            is GameAction.Continue, is GameAction.NewGame -> currentState
        }

        return if (nextState != currentState) {
            nextState.copy(lastAction = LastActionInfo(playerId, action))
        } else {
            nextState
        }
    }

    private fun handleEnvido(currentState: GameState, playerId: String, amount: Int): GameState {
        val bettingPlayer = currentState.players.find { it.id == playerId } ?: return currentState
        val opponentTeam = if (bettingPlayer.team == "teamA") "teamB" else "teamA"

        // 1. Obtenemos la lista de TODOS los jugadores aptos para este lance
        val eligiblePlayers = getEligiblePlayersForLance(currentState)

        // 2. De esa lista, filtramos a los oponentes en su orden de turno natural
        val eligibleOpponents = getTurnOrderedPlayers(currentState.players, currentState.manoPlayerId)
            .filter { it.team == opponentTeam && it in eligiblePlayers }
            .map { it.id }

        // Si no hay oponentes aptos, la acción no es válida (no se puede envidar a nadie)
        if (eligibleOpponents.isEmpty()) {
            return currentState
        }

        val totalAmount = (currentState.currentBet?.amount ?: 0) + amount
        val newBet = BetInfo(
            amount = totalAmount,
            bettingPlayerId = playerId,
            respondingPlayerId = eligibleOpponents.first() // El primer oponente apto responde
        )

        return currentState.copy(
            currentBet = newBet,
            currentTurnPlayerId = eligibleOpponents.first(),
            betInitiatorTeam = bettingPlayer.team,
            playersPendingResponse = eligibleOpponents, // La lista ahora solo contiene oponentes aptos
            availableActions = listOf(GameAction.Quiero, GameAction.NoQuiero, GameAction.Envido(2), GameAction.Órdago),
            playersWhoPassed = emptySet()
        )
    }


    private fun handleQuiero(currentState: GameState): GameState {
        // Si la apuesta actual es un órdago, se resuelve la partida inmediatamente.
        if (currentState.currentBet?.isOrdago == true) {
            return resolveOrdagoShowdown(currentState)
        }

        // Si no, es un envite normal.
        val resolvedState = currentState.copy(betInitiatorTeam = null, playersPendingResponse = emptyList())
        return endLanceAndAdvance(resolvedState) {
            val bet = this.currentBet ?: return@endLanceAndAdvance this
            val newAgreedBets = this.agreedBets + (this.gamePhase to bet.amount)
            Log.d("MusVistoTest", "Bet of ${bet.amount} ACCEPTED for ${this.gamePhase}.")
            this.copy(agreedBets = newAgreedBets)
        }
    }

    private fun handleNoQuiero(currentState: GameState, playerId: String): GameState {
        // Si no hay una apuesta activa, la acción no es válida.
        if (currentState.currentBet == null || playerId !in currentState.playersPendingResponse) {
            return currentState
        }

        // Eliminamos al jugador actual de la lista de los que faltan por responder.
        val remainingResponders = currentState.playersPendingResponse.filter { it != playerId }

        // Si ya no quedan miembros del equipo por responder, el rechazo es definitivo.
        if (remainingResponders.isEmpty()) {
            Log.d("MusVistoTest", "El equipo entero ha rechazado la apuesta.")
            val resolvedState = currentState.copy(betInitiatorTeam = null, playersPendingResponse = emptyList())
            return endLanceAndAdvance(resolvedState) {
                val bet = this.currentBet ?: return@endLanceAndAdvance this
                val bettingPlayer = this.players.find { p -> p.id == bet.bettingPlayerId } ?: return@endLanceAndAdvance this
                val pointsWon = this.agreedBets[this.gamePhase] ?: 1
                val currentScore = this.score[bettingPlayer.team] ?: 0
                val newScore = this.score + (bettingPlayer.team to currentScore + pointsWon)
                Log.d("MusVistoTest", "APUESTA RECHAZADA. El equipo ${bettingPlayer.team} gana $pointsWon punto(s).")
                this.copy(score = newScore)
            }
        } else {
            // Si aún queda un compañero por hablar, le pasamos el turno a él.
            Log.d("MusVistoTest", "Jugador $playerId rechaza, pero pasa el turno a su compañero.")
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
                playersWhoPassed = emptySet(), // Reset for discard confirmation
                currentTurnPlayerId = newState.manoPlayerId // Turn returns to "mano"
            )
        } else {
            // If not, it's the next player's turn to decide on Mus
            setNextPlayerTurn(newState)
        }
    }

    private fun handleNoMus(currentState: GameState): GameState {
        return currentState.copy(
            gamePhase = GamePhase.GRANDE,
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago),
            playersWhoPassed = emptySet(),
            discardCounts = emptyMap(),
            currentBet = null,
            currentTurnPlayerId = currentState.manoPlayerId

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

        val eligibleOpponents = getEligiblePlayersForLance(currentState)
            .filter { it.team == opponentTeam }
            .map { it.id }

        if (eligibleOpponents.isEmpty()) {
            return currentState // No se puede cantar órdago si no hay oponentes aptos
        }

        // El órdago se juega los 40 puntos del juego.
        val newBet = BetInfo(
            amount = 40,
            bettingPlayerId = playerId,
            respondingPlayerId = eligibleOpponents.first(),
            isOrdago = true // Marcamos la apuesta como un órdago
        )

        return currentState.copy(
            currentBet = newBet,
            currentTurnPlayerId = eligibleOpponents.first(),
            betInitiatorTeam = bettingPlayer.team,
            playersPendingResponse = eligibleOpponents,
            // Al órdago solo se puede responder con "Quiero" o "No Quiero".
            availableActions = listOf(GameAction.Quiero, GameAction.NoQuiero),
            playersWhoPassed = emptySet()
        )
    }

    private fun resolveOrdagoShowdown(currentState: GameState): GameState {
        Log.d("MusVistoTest", "¡ÓRDAGO ACEPTADO! Resolviendo la partida...")

        // Determinamos quién habría ganado el lance actual
        val lanceWinner: Player? = when (currentState.gamePhase) {
            GamePhase.GRANDE -> getGrandeWinner(currentState)
            GamePhase.CHICA -> getChicaWinner(currentState)
            GamePhase.PARES -> getParesWinner(currentState)
            GamePhase.JUEGO -> getJuegoWinner(currentState)
            else -> null
        }

        if (lanceWinner == null) {
            // Esto no debería ocurrir, pero como salvaguarda, gana el equipo que lo cantó.
            val bettingPlayer = currentState.players.find { it.id == currentState.currentBet?.bettingPlayerId }
            val finalScore = currentState.score.toMutableMap()
            if (bettingPlayer != null) {
                finalScore[bettingPlayer.team] = 40
            }
            return currentState.copy(score = finalScore, gamePhase = GamePhase.GAME_OVER)
        }

        val winningTeam = lanceWinner.team
        val finalScore = currentState.score.toMutableMap()
        finalScore[winningTeam] = 40 // El equipo ganador se lleva los 40 puntos

        Log.d("MusVistoTest", "El ganador del lance es ${lanceWinner.name}. ¡El equipo $winningTeam gana la partida!")

        return currentState.copy(
            score = finalScore,
            gamePhase = GamePhase.GAME_OVER,
            winningTeam = winningTeam,
            availableActions = listOf(GameAction.NewGame),
            revealAllHands = true
        )
    }

    private fun endLanceAndAdvance(currentState: GameState, updates: GameState.() -> GameState): GameState {
        val updatedState = currentState.updates()
        var nextPhase = advanceToNextPhase(updatedState.gamePhase)

        var finalState = updatedState.copy(
            gamePhase = nextPhase,
            currentTurnPlayerId = updatedState.manoPlayerId,
            playersWhoPassed = emptySet(),
            currentBet = null
        )

        // --- LÓGICA DE SALTO DE LANCE MEJORADA ---

        // Comprobación de Pares
        if (nextPhase == GamePhase.PARES) {
            val playersWithPares = finalState.players.filter { getHandPares(it.hand).strength > 0 }
            if (playersWithPares.isEmpty()) {
                Log.d("MusVistoTest", "PARES: Nadie tiene. Saltando a Juego.")
                return endLanceAndAdvance(finalState) { this } // Llama recursivamente para saltar a Juego
            }
            // Comprueba si todos los que tienen pares son del mismo equipo
            val teamsWithPares = playersWithPares.map { it.team }.distinct()
            if (teamsWithPares.size == 1) {
                Log.d("MusVistoTest", "PARES: Solo el equipo ${teamsWithPares.first()} tiene. Ganan puntos y se salta el lance.")
                val scoredState = scoreParesPlays(finalState) // Suma los puntos de las jugadas de pares
                return endLanceAndAdvance(scoredState) { this } // Llama recursivamente para saltar a Juego
            }
        }

        // Comprobación de Juego
        if (nextPhase == GamePhase.JUEGO) {
            val playersWithJuego = finalState.players.filter { getHandJuegoValue(it.hand) >= 31 }
            if (playersWithJuego.isEmpty()) {
                // Nadie tiene Juego, se juega al Punto
                finalState = finalState.copy(isPuntoPhase = true)
            } else {
                // Alguien tiene Juego, se juega a Juego
                finalState = finalState.copy(isPuntoPhase = false)
                val teamsWithJuego = playersWithJuego.map { it.team }.distinct()
                if (teamsWithJuego.size == 1) {
                    Log.d("MusVistoTest", "JUEGO: Solo el equipo ${teamsWithJuego.first()} tiene. Ganan puntos y se acaba la ronda.")
                    val scoredState = scoreJuegoPlays(finalState) // Suma los puntos de las jugadas de juego
                    return endLanceAndAdvance(scoredState) { this } // Llama recursivamente para terminar la ronda
                }
            }
        }

        return finalState.copy(
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
    }


    private fun advanceToNextPhase(currentPhase: GamePhase): GamePhase {
        return when (currentPhase) {
            GamePhase.GRANDE -> GamePhase.CHICA
            GamePhase.CHICA -> GamePhase.PARES // It will now go to PARES first
            GamePhase.PARES -> GamePhase.JUEGO
            GamePhase.JUEGO -> GamePhase.ROUND_OVER
            else -> currentPhase // Should not happen
        }
    }

    private fun setNextPlayerTurn(currentState: GameState): GameState {
        val currentPlayerId = currentState.currentTurnPlayerId ?: return currentState
        var currentIndex = currentState.players.indexOfFirst { it.id == currentPlayerId }
        if (currentIndex == -1) return currentState

        val eligiblePlayers = currentState.players.filter { player ->
            when (currentState.gamePhase) {
                GamePhase.PARES -> getHandPares(player.hand).strength > 0
                GamePhase.JUEGO -> if (currentState.isPuntoPhase) true else getHandJuegoValue(player.hand) >= 31
                else -> true
            }
        }

        if (eligiblePlayers.isEmpty()) {
            return endLanceAndAdvance(currentState) { this }
        }

        // Find the next eligible player in the original turn order
        for (i in 1 until currentState.players.size) {
            val nextPlayer = currentState.players[(currentIndex - i + currentState.players.size) % currentState.players.size]
            if (nextPlayer in eligiblePlayers) {
                return currentState.copy(currentTurnPlayerId = nextPlayer.id)
            }
        }

        // If we loop through everyone and only the current player is eligible, the lance is over
        return endLanceAndAdvance(currentState) { this }
    }

    private fun handleDiscard(currentState: GameState, playerId: String): GameState {
        val player = currentState.players.find { it.id == playerId } ?: return currentState
        val cardsToDiscard = currentState.selectedCardsForDiscard
        var event: GameEvent? = null

        if (currentState.gamePhase == GamePhase.DISCARD && currentState.selectedCardsForDiscard.isEmpty()) {
            Log.e("MusGameLogic", "Error: Intento de descarte vacío en fase de Mus por el jugador $playerId.")
            return currentState // Devuelve el estado sin cambios.
        }


        var deck = currentState.deck
        var discardPile = currentState.discardPile
        val cardsNeeded = cardsToDiscard.size
        var newCards: List<Card>


        // --- LÓGICA CORREGIDA Y SIMPLIFICADA ---
        if (deck.size < cardsNeeded) {
            Log.d("MusVistoTest", "Deck empty! Shuffling discard pile of ${discardPile.size} cards.")
            event = GameEvent.DISCARD_PILE_SHUFFLED
            val fromOldDeck = deck

            // El nuevo mazo es la pila de descartes barajada.
            deck = discardPile.shuffled(random.get())
            // La pila de descartes se vacía.
            discardPile = emptyList()

            val neededFromNewDeck = cardsNeeded - fromOldDeck.size
            newCards = fromOldDeck + deck.take(neededFromNewDeck)
            deck = deck.drop(neededFromNewDeck) // Actualizamos el nuevo mazo
        } else {
            newCards = deck.take(cardsNeeded)
            deck = deck.drop(cardsNeeded)
        }

        // Las cartas que acaba de tirar el jugador se añaden a la nueva pila de descartes.
        discardPile += cardsToDiscard

        val newHand = player.hand.filterNot { it in cardsToDiscard } + newCards

        val updatedPlayer = player.copy(hand = newHand)
        val updatedPlayers = currentState.players.map { if (it.id == playerId) updatedPlayer else it }

        val newPassedSet = currentState.playersWhoPassed + playerId
        val newDiscardCounts = currentState.discardCounts + (playerId to cardsToDiscard.size)

        // Comprobamos si todos han descartado para romper el bucle
        if (newPassedSet.size == updatedPlayers.size) {
            return currentState.copy(
                players = updatedPlayers,
                deck = deck,
                discardPile = discardPile,
                gamePhase = GamePhase.MUS_DECISION,
                availableActions = listOf(GameAction.Mus, GameAction.NoMus),
                selectedCardsForDiscard = emptySet(),
                playersWhoPassed = emptySet(),
                discardCounts = newDiscardCounts,
                currentTurnPlayerId = currentState.manoPlayerId
            )
        }

        // Si no, pasa el turno al siguiente jugador
        return setNextPlayerTurn(currentState).copy(
            players = updatedPlayers,
            deck = deck,
            discardPile = discardPile,
            selectedCardsForDiscard = emptySet(),
            playersWhoPassed = newPassedSet,
            discardCounts = newDiscardCounts,
            event = event

        )
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
        var finalScore = currentState.score.toMutableMap()

        // 1. Score "Grande"
        getGrandeWinner(gameState = currentState)?.let { winner ->
            val points = currentState.agreedBets[GamePhase.GRANDE] ?: 0
            if (points > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + points
                Log.d("MusVistoTest", "GRANDE: Team ${winner.team} wins $points points.")
            }
        }

        // 2. Score "Chica"
        getChicaWinner(gameState = currentState)?.let { winner ->
            val points = currentState.agreedBets[GamePhase.CHICA] ?: 0
            if (points > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + points
                Log.d("MusVistoTest", "CHICA: Team ${winner.team} wins $points points.")
            }
        }

        // 3. Score "Pares" (Points for having the best pares, plus points for the bet)
        getParesWinner(gameState = currentState)?.let { winner ->
            // Points for the bet
            val betPoints = currentState.agreedBets[GamePhase.PARES] ?: 0
            if (betPoints > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + betPoints
                Log.d("MusVistoTest", "PARES (Bet): Team ${winner.team} wins $betPoints points.")
            }
            // Points for the plays themselves
            currentState.players.forEach { player ->
                val play = getHandPares(player.hand)
                val playPoints = when (play) {
                    is ParesPlay.Duples -> 3
                    is ParesPlay.Medias -> 2
                    is ParesPlay.Pares -> 1
                    else -> 0
                }
                if (playPoints > 0) {
                    val currentScore = finalScore[player.team] ?: 0
                    finalScore[player.team] = currentScore + playPoints
                    Log.d("MusVistoTest", "PARES (Play): Team ${player.team} scores $playPoints points for ${play::class.simpleName}.")
                }
            }
        }

        // 4. Score "Juego" (Similar to Pares)
        getJuegoWinner(gameState = currentState)?.let { winner ->
            // Points for the bet
            val betPoints = currentState.agreedBets[GamePhase.JUEGO] ?: 0
            if (betPoints > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + betPoints
                Log.d("MusVistoTest", "JUEGO (Bet): Team ${winner.team} wins $betPoints points.")
            }
            // Points for the plays themselves
            currentState.players.forEach { player ->
                if ((getHandJuegoValue(player.hand)) != 31) {
                    val playPoints = when (getHandJuegoValue(player.hand)) {
                        32, 40, 37, 36, 35, 34, 33 -> 2
                        else -> 0
                    }
                    if (playPoints > 0) {
                        val currentScore = finalScore[player.team] ?: 0
                        finalScore[player.team] = currentScore + playPoints
                        Log.d("MusVistoTest", "JUEGO (Play): Team ${player.team} scores $playPoints points.")
                    }
                } else {
                    val playPoints = 3
                    val currentScore = finalScore[player.team] ?: 0
                    finalScore[player.team] = currentScore + playPoints
                    Log.d("MusVistoTest", "JUEGO (Play): Team ${player.team} scores $playPoints points.")
                }
            }
        }


        return currentState.copy(score = finalScore,
            manoPlayerId = currentState.manoPlayerId )
    }

     internal fun getTurnOrderedPlayers(players: List<Player>, manoId: String): List<Player> {
        val manoIndex = players.indexOfFirst { it.id == manoId }
        if (manoIndex == -1) return players
        return players.drop(manoIndex) + players.take(manoIndex)
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
}
