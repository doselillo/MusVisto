package com.doselfurioso.musvisto.model

data class Player(
    val id: String, // In the future, this will be the unique user ID
    val name: String,
    val hand: List<Card> = emptyList(), // The player's hand of cards, initially empty
    val avatarResId: Int,
    val isAi: Boolean = false,
    val team: String// Whether the player is an AI or a human
)
