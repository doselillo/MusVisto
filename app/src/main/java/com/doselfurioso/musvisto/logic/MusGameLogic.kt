package com.doselfurioso.musvisto.logic

import android.util.Log
import com.doselfurioso.musvisto.model.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusGameLogic @Inject constructor() {

    // Creates a standard 40-card Spanish deck.
    fun createDeck(): List<Card> {
        // flatMap combines the lists created for each suit into a single list
        return Suit.values().flatMap { suit ->
            // map creates a list of cards for the current suit
            Rank.values().map { rank ->
                Card(suit, rank)
            }
        }
    }

    // Shuffles the deck randomly.
    fun shuffleDeck(deck: List<Card>): List<Card> {
        return deck.shuffled()
    }

    // Deals 4 cards to each of the 4 players.
    fun dealCards(
        players: List<Player>,
        deck: List<Card>
    ): Pair<List<Player>, List<Card>> {
        if (players.size != 4) throw IllegalArgumentException("4 players are required.")

        var remainingDeck = deck
        val updatedPlayers = players.map { player ->
            // Take the top 4 cards for the hand
            val hand = remainingDeck.take(4)
            // Remove those 4 cards from the deck
            remainingDeck = remainingDeck.drop(4)
            // Return a new Player object with the updated hand
            player.copy(hand = hand)
        }

        // Return the list of players with their hands and the rest of the deck
        return Pair(updatedPlayers, remainingDeck)
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
    fun getGrandeWinner(players: List<Player>): Player? {
        if (players.isEmpty()) return null

        // We use 'reduce' to compare each player against the current winner.
        // The first player in the list is the initial winner.
        return players.reduce { currentWinner, nextPlayer ->
            val winnerCard = getHighestCard(currentWinner.hand)
            val nextPlayerCard = getHighestCard(nextPlayer.hand)

            // If the next player has no card, the current winner stays.
            if (nextPlayerCard == null) return@reduce currentWinner
            // If the current winner has no card, the next player wins.
            if (winnerCard == null) return@reduce nextPlayer

            // The core logic: the next player only wins if their card is STRICTLY better.
            // In case of a tie (value is equal), the 'currentWinner' (who is "mano") keeps the lead.
            if (nextPlayerCard.rank.value > winnerCard.rank.value) {
                nextPlayer // The new winner
            } else {
                currentWinner // The current winner holds
            }
        }
    }

    /**
     * Determines the winner of the "Chica" lance among all players.
     * It considers the player order ("mano") to break ties.
     */
    fun getChicaWinner(players: List<Player>): Player? {
        if (players.isEmpty()) return null

        // The logic is the same as Grande, but we use getLowestCard and compare for a smaller value.
        return players.reduce { currentWinner, nextPlayer ->
            val winnerCard = getLowestCard(currentWinner.hand)
            val nextPlayerCard = getLowestCard(nextPlayer.hand)

            // If the next player has no card, the current winner stays.
            if (nextPlayerCard == null) return@reduce currentWinner
            // If the current winner has no card, the next player wins.
            if (winnerCard == null) return@reduce nextPlayer

            // The core logic: the next player only wins if their card is STRICTLY smaller.
            // In case of a tie, the 'currentWinner' (who is "mano") keeps the lead.
            if (nextPlayerCard.rank.value < winnerCard.rank.value) {
                nextPlayer // The new winner
            } else {
                currentWinner // The current winner holds
            }
        }
    }

    /**
     * Analyzes a hand and returns the corresponding ParesPlay.
     */
    fun getHandPares(hand: List<Card>): ParesPlay {
        // Group cards by their rank and count them.
        // Example: {REY=3, AS=1}
        val counts = hand.groupingBy { it.rank }.eachCount()

        // Filter for ranks that appear 2 or more times.
        val pairsOrBetter = counts.filter { it.value >= 2 }

        if (pairsOrBetter.isEmpty()) {
            return ParesPlay.NoPares
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
    fun getParesWinner(players: List<Player>): Player? {
        // First, filter out players who don't have at least Pares.
        val playersWithPares = players.filter { getHandPares(it.hand).strength > 0 }

        // If no one has Pares, there is no winner for this lance.
        if (playersWithPares.isEmpty()) return null

        // Now, find the winner among those who do have Pares.
        return playersWithPares.reduce { currentWinner, nextPlayer ->
            val winnerPlay = getHandPares(currentWinner.hand)
            val nextPlay = getHandPares(nextPlayer.hand)

            // 1. Compare by the STRENGTH of the play first (Duples > Medias > Pares)
            if (nextPlay.strength > winnerPlay.strength) {
                return@reduce nextPlayer // The next player has a better type of play
            }
            if (winnerPlay.strength > nextPlay.strength) {
                return@reduce currentWinner // The current winner has a better type of play
            }

            // 2. If strength is equal, compare by the RANK of the cards.
            // The 'when' statement handles each type of play.
            val nextPlayerWinsTie = when (winnerPlay) {
                is ParesPlay.Duples -> {
                    val nextDuples = nextPlay as ParesPlay.Duples
                    // First, compare the high pair. If they are equal, compare the low pair.
                    if (nextDuples.highPair.value > winnerPlay.highPair.value) true
                    else if (nextDuples.highPair.value == winnerPlay.highPair.value) {
                        nextDuples.lowPair.value > winnerPlay.lowPair.value
                    } else false
                }
                is ParesPlay.Medias -> {
                    val nextMedias = nextPlay as ParesPlay.Medias
                    nextMedias.rank.value > winnerPlay.rank.value
                }
                is ParesPlay.Pares -> {
                    val nextPares = nextPlay as ParesPlay.Pares
                    nextPares.rank.value > winnerPlay.rank.value
                }
                is ParesPlay.NoPares -> false // Should not happen due to the filter above
            }

            if (nextPlayerWinsTie) {
                nextPlayer
            } else {
                // 3. If everything is identical, the currentWinner holds (mano wins).
                currentWinner
            }
        }
    }

    /**
     * Calculates the total value of a hand for the "Juego" lance.
     * Figures (Sota, Caballo, Rey) are worth 10. Other cards are worth their face value.
     */
    private fun getHandJuegoValue(hand: List<Card>): Int {
        return hand.sumOf { card ->
            when (card.rank) {
                Rank.SOTA, Rank.CABALLO, Rank.REY -> 10
                else -> card.rank.value
            }
        }
    }

    /**
     * Determines the winner of the "Juego" or "Punto" lance.
     */
    fun getJuegoWinner(players: List<Player>): Player? {
        if (players.isEmpty()) return null

        // Calculate the score for each player just once to be efficient.
        val playerScores = players.map { player ->
            player to getHandJuegoValue(player.hand)
        }

        // Partition players into two groups: those with Juego and those without.
        val (playersWithJuego, playersAtPunto) = playerScores.partition { it.second >= 31 }

        return if (playersWithJuego.isNotEmpty()) {
            // --- SCENARIO 1: At least one player has "Juego" ---
            playersWithJuego.sortedWith(
                // We need a custom comparator for Juego's special rules.
                compareByDescending<Pair<Player, Int>> { (_, score) -> score == 31 } // 31 is the best
                    .thenByDescending { (_, score) -> score == 32 } // 32 is second best
                    .thenByDescending { (_, score) -> score } // For the rest, higher is better
            ).firstOrNull()?.first // .first gives us the winning Pair, the second .first gives us the Player from the pair

        } else {
            // --- SCENARIO 2: No one has "Juego", so we play for "Punto" ---
            // The winner is the one with the highest score (closest to 30).
            playersAtPunto.maxByOrNull { it.second }?.first
        }
    }

    fun processAction(currentState: GameState, action: GameAction, playerId: String): GameState {
        if (currentState.currentTurnPlayerId != playerId) return currentState

        return when (action) {
            is GameAction.Mus -> handleMus(currentState, playerId)
            is GameAction.NoMus -> handleNoMus(currentState)
            is GameAction.Paso -> handlePaso(currentState, playerId)
            is GameAction.Envido -> handleEnvido(currentState, playerId, action.amount)
            is GameAction.Quiero -> handleQuiero(currentState)
            is GameAction.NoQuiero -> handleNoQuiero(currentState)
            is GameAction.Órdago -> handleOrdago(currentState, playerId)
            is GameAction.ConfirmDiscard -> handleDiscard(currentState, playerId) // <-- AÑADE ESTO
            else -> currentState
        }
    }

    private fun handleEnvido(currentState: GameState, playerId: String, amount: Int): GameState {
        val bettingPlayer = currentState.players.find { it.id == playerId } ?: return currentState

        // Find the next player from the opposing team to respond
        val respondingPlayer = findNextOpponent(currentState, bettingPlayer) ?: return currentState

        // Check if this is a raise (re-envite)
        val totalAmount = (currentState.currentBet?.amount ?: 0) + amount

        val newBet = BetInfo(
            amount = totalAmount,
            bettingPlayerId = playerId,
            respondingPlayerId = respondingPlayer.id
        )

        return currentState.copy(
            currentBet = newBet,
            // The responding player has a new set of actions available
            availableActions = listOf(GameAction.Quiero, GameAction.NoQuiero, GameAction.Envido(2), GameAction.Órdago),
            currentTurnPlayerId = respondingPlayer.id, // The turn goes to the responder
            playersWhoPassed = emptySet() // A bet resets who has passed
        )
    }

    private fun handleQuiero(currentState: GameState): GameState {
        val bet = currentState.currentBet ?: return currentState

        val newAgreedBets = currentState.agreedBets.toMutableMap().apply {
            this[currentState.gamePhase] = bet.amount
        }

        Log.d("MusVistoTest", "Bet of ${bet.amount} ACCEPTED for ${currentState.gamePhase}.")

        val nextState = advanceToNextPhase(currentState.copy(agreedBets = newAgreedBets))

        // If advancing the phase leads to scoring, score immediately.
        return if (nextState.gamePhase == GamePhase.SCORING) {
            scoreRound(nextState.copy(currentBet = null, playersWhoPassed = emptySet()))
        } else {
            nextState.copy(
                currentBet = null,
                playersWhoPassed = emptySet(),
                currentTurnPlayerId = currentState.players.first().id
            )
        }
    }

    private fun handleNoQuiero(currentState: GameState): GameState {
        val bet = currentState.currentBet ?: return currentState
        val bettingPlayer = currentState.players.find { it.id == bet.bettingPlayerId } ?: return currentState

        val pointsWon = currentState.agreedBets[currentState.gamePhase] ?: 1
        val currentScore = currentState.score[bettingPlayer.team] ?: 0
        val newScore = currentState.score.toMutableMap().apply {
            this[bettingPlayer.team] = currentScore + pointsWon
        }

        Log.d("MusVistoTest", "Bet REJECTED. Team ${bettingPlayer.team} wins $pointsWon point(s).")

        val nextState = advanceToNextPhase(currentState.copy(score = newScore))

        // If advancing the phase leads to scoring, score immediately.
        return if (nextState.gamePhase == GamePhase.SCORING) {
            scoreRound(nextState.copy(currentBet = null, playersWhoPassed = emptySet()))
        } else {
            nextState.copy(
                currentBet = null,
                playersWhoPassed = emptySet(),
                currentTurnPlayerId = currentState.players.first().id
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
                currentTurnPlayerId = newState.players.first().id // Turn returns to "mano"
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
            playersWhoPassed = emptySet()
        )
    }

    private fun handlePaso(currentState: GameState, playerId: String): GameState {
        val newPassedSet = currentState.playersWhoPassed + playerId

        if (newPassedSet.size == currentState.players.size) {
            val nextState = advanceToNextPhase(currentState)

            // If advancing the phase leads to scoring, score immediately.
            return if (nextState.gamePhase == GamePhase.SCORING) {
                scoreRound(nextState.copy(playersWhoPassed = emptySet()))
            } else {
                nextState.copy(
                    playersWhoPassed = emptySet(),
                    currentTurnPlayerId = currentState.players.first().id
                )
            }
        }

        return setNextPlayerTurn(currentState).copy(
            playersWhoPassed = newPassedSet
        )
    }


    private fun handleOrdago(currentState: GameState, playerId: String): GameState {
        Log.d("MusVistoTest", "ÓRDAGO!!!")
        // Placeholder logic for now
        return currentState
    }


    private fun advanceToNextPhase(currentState: GameState): GameState {
        val nextPhase = when (currentState.gamePhase) {
            GamePhase.GRANDE -> GamePhase.CHICA
            GamePhase.CHICA -> GamePhase.PARES
            GamePhase.PARES -> GamePhase.JUEGO
            GamePhase.JUEGO -> GamePhase.SCORING
            else -> GamePhase.SCORING // End of the round
        }
        // Check for Pares before entering the Pares phase
        if (nextPhase == GamePhase.PARES) {
            val playersWithPares = currentState.players.filter { getHandPares(it.hand).strength > 0 }
            if (playersWithPares.isEmpty()) {
                Log.d("MusVistoTest", "PARES: No one has pairs. Skipping to JUEGO.")
                return advanceToNextPhase(currentState.copy(gamePhase = GamePhase.PARES)) // Recursive call to skip
            }
        }

        // Check for Juego/Punto before entering the Juego phase
        if (nextPhase == GamePhase.JUEGO) {
            val hasJuego = currentState.players.any { getHandJuegoValue(it.hand) >= 31 }
            return currentState.copy(
                gamePhase = GamePhase.JUEGO,
                isPuntoPhase = !hasJuego, // If no one has Juego, it's a Punto round
                availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
            )
        }

        return currentState.copy(
            gamePhase = nextPhase,
            // Every new lance starts with the same betting options
            availableActions = listOf(GameAction.Paso, GameAction.Envido(2), GameAction.Órdago)
        )
    }

    private fun setNextPlayerTurn(currentState: GameState): GameState {
        val currentPlayerId = currentState.currentTurnPlayerId ?: return currentState
        val currentPlayerIndex = currentState.players.indexOfFirst { it.id == currentPlayerId }
        if (currentPlayerIndex == -1) return currentState

        val nextPlayerIndex = (currentPlayerIndex + 1) % currentState.players.size
        val nextPlayerId = currentState.players[nextPlayerIndex].id

        return currentState.copy(
            currentTurnPlayerId = nextPlayerId
        )
    }

    private fun handleDiscard(currentState: GameState, playerId: String): GameState {
        val player = currentState.players.find { it.id == playerId } ?: return currentState
        val cardsToDiscard = currentState.selectedCardsForDiscard

        // Do nothing if no cards are selected
        if (cardsToDiscard.isEmpty()) return currentState

        val newHand = player.hand.filterNot { it in cardsToDiscard }

        val cardsNeeded = cardsToDiscard.size
        val newCards = currentState.deck.take(cardsNeeded)
        val remainingDeck = currentState.deck.drop(cardsNeeded)

        val updatedPlayer = player.copy(hand = newHand + newCards)
        val updatedPlayers = currentState.players.map { if (it.id == playerId) updatedPlayer else it }

        // After this player discards, pass the turn to the next
        return setNextPlayerTurn(currentState).copy(
            players = updatedPlayers,
            deck = remainingDeck,
            selectedCardsForDiscard = emptySet() // Reset selection
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
        getGrandeWinner(currentState.players)?.let { winner ->
            val points = currentState.agreedBets[GamePhase.GRANDE] ?: 0
            if (points > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + points
                Log.d("MusVistoTest", "GRANDE: Team ${winner.team} wins $points points.")
            }
        }

        // 2. Score "Chica"
        getChicaWinner(currentState.players)?.let { winner ->
            val points = currentState.agreedBets[GamePhase.CHICA] ?: 0
            if (points > 0) {
                val currentScore = finalScore[winner.team] ?: 0
                finalScore[winner.team] = currentScore + points
                Log.d("MusVistoTest", "CHICA: Team ${winner.team} wins $points points.")
            }
        }

        // 3. Score "Pares" (Points for having the best pares, plus points for the bet)
        getParesWinner(currentState.players)?.let { winner ->
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
        getJuegoWinner(currentState.players)?.let { winner ->
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


        return currentState.copy(score = finalScore)
    }


}
