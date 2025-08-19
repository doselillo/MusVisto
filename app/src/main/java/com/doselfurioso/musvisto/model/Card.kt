package com.doselfurioso.musvisto.model

// Defines the suits and ranks of the Spanish deck.
enum class Suit {
    OROS, COPAS, ESPADAS, BASTOS
}

enum class Rank(val value: Int) {
    AS(1), DOS(2), TRES(3), CUATRO(4),
    CINCO(5), SEIS(6), SIETE(7), SOTA(10),
    CABALLO(11), REY(12)
}

// The data class for a single card.
data class Card(val suit: Suit, val rank: Rank)
