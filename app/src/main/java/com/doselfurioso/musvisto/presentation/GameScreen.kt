package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.Card as CardData
import com.doselfurioso.musvisto.model.Player

// Composable for a single card (no changes here)
@Composable
fun GameCard(card: CardData) {
    Card(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(80.dp)
            .aspectRatio(0.7f),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Image(
            painter = cardToPainter(card = card),
            contentDescription = "${card.rank} of ${card.suit}"
        )
    }
}

// Composable for the back of a card (no changes here)
@Composable
fun CardBack() {
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp) // Changed to vertical padding for side hands
            .height(80.dp) // For side hands, it's better to control height
            .aspectRatio(0.7f),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.card_back),
            contentDescription = "Card back"
        )
    }
}

// MODIFIED: Composable for the player's hand with the "fanned" effect
@Composable
fun PlayerHandFanned(cards: List<CardData>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp), // Increased padding to lift the hand a bit
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val rotation = (index - cardCount / 2f) * 8f
            val offsetY = (kotlin.math.abs(index - cardCount / 2f) * -12f).dp

            // THE KEY CHANGE: We add translationX to spread the cards horizontally
            val translationX = (index - cardCount / 2f) * 40f

            Box(
                modifier = Modifier
                    .offset(y = offsetY)
                    .graphicsLayer {
                        this.rotationZ = rotation
                        this.translationX = translationX
                    }
            ) {
                GameCard(card = card)
            }
        }
    }
}

// MODIFIED: Opponent hands are simpler now
@Composable
fun TopOpponentHand() {
    Row { repeat(4) { CardBack() } }
}

@Composable
fun SideOpponentHand() {
    Column { repeat(4) { CardBack() } }
}


// MODIFIED: The main screen Composable with the corrected layout
@Composable
fun GameScreen(
    gameViewModel: GameViewModel = viewModel()
) {
    val gameState by gameViewModel.gameState.collectAsState()
    val players = gameState.players

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF006A4E)) // A classic green table color
    ) {
        if (players.size == 4) {
            val player = players[0]

            // Player's Hand (Bottom Center)
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                PlayerHandFanned(cards = player.hand)
            }

            // Partner's Hand (Top Center)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                TopOpponentHand()
            }

            // Rival Left (Center Start)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                SideOpponentHand()
            }

            // Rival Right (Center End)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            ) {
                SideOpponentHand()
            }
        }
    }
}