package com.doselfurioso.musvisto.logic

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
        // First, ensure the action is valid for the current player and phase
        if (currentState.currentTurnPlayerId != playerId || !currentState.availableActions.contains(action)) {
            return currentState // If action is not valid, do nothing
        }

        return when (action) {
            is GameAction.Mus, is GameAction.NoMus -> {
                // Logic for the Mus decision phase will go here.
                // For now, let's just move to the next phase as a placeholder.
                val nextState = currentState.copy(
                    gamePhase = GamePhase.GRANDE,
                    availableActions = listOf(GameAction.Paso, GameAction.Envido, GameAction.Órdago)
                )
                // We need to determine the next player's turn
                setNextPlayerTurn(nextState)
            }
            // We will add cases for other actions (Envido, Quiero, etc.) later
            else -> currentState
        }
    }

    private fun setNextPlayerTurn(currentState: GameState): GameState {
        val currentPlayerId = currentState.currentTurnPlayerId ?: return currentState
        val currentPlayerIndex = currentState.players.indexOfFirst { it.id == currentPlayerId }

        if (currentPlayerIndex == -1) return currentState // Player not found

        val nextPlayerIndex = (currentPlayerIndex + 1) % currentState.players.size
        val nextPlayer = currentState.players[nextPlayerIndex]

        return currentState.copy(
            currentTurnPlayerId = nextPlayer.id
            // We would also update availableActions for the next player here
        )
    }

}