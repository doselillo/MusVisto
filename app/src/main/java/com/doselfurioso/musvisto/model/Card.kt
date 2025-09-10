package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

@Serializable
enum class Suit {
    OROS, COPAS, ESPADAS, BASTOS
}

@Serializable
enum class Rank(val value: Int) {
    AS(1), DOS(1), TRES(12), CUATRO(4),
    CINCO(5), SEIS(6), SIETE(7), SOTA(10),
    CABALLO(11), REY(12)
}

@Serializable
data class Card(val suit: Suit, val rank: Rank)
