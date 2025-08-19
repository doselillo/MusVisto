package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.Card as CardData

// Custom modifier for the bottom border (no changes here)
fun Modifier.bottomBorder(strokeWidth: Dp, color: Color) = composed(
    factory = {
        val density = LocalDensity.current
        val strokeWidthPx = density.run { strokeWidth.toPx() }

        Modifier.drawBehind {
            val width = size.width
            val height = size.height - strokeWidthPx / 2

            drawLine(
                color = color,
                start = Offset(x = 0f, y = height),
                end = Offset(x = width, y = height),
                strokeWidth = strokeWidthPx
            )
        }
    }
)

// MODIFIED: Composable for a single card without the Surface wrapper
@Composable
fun GameCard(card: CardData) {
    Image(
        painter = cardToPainter(card = card),
        contentDescription = "${card.rank} of ${card.suit}",
        modifier = Modifier
            .width(80.dp)
            .aspectRatio(0.7f)
            .padding(horizontal = 4.dp)
            .shadow(elevation = 1.dp, shape = RoundedCornerShape(4.dp)) // Add shadow directly
            .clip(RoundedCornerShape(4.dp)) // Clip the image to have rounded corners
    )
}

// MODIFIED: Composable for the back of a card without the Surface wrapper
@Composable
fun CardBack() {
    Image(
        painter = painterResource(id = R.drawable.card_back),
        contentDescription = "Card back",
        modifier = Modifier
            .height(80.dp)
            .aspectRatio(0.7f)
            .padding(vertical = 4.dp)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
    )
}


// --- The rest of the file (PlayerHandFanned, OpponentHand, GameScreen) remains the same ---
@Composable
fun PlayerHandFanned(cards: List<CardData>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val rotation = (index - cardCount / 2f) * 8f
            val offsetY = (kotlin.math.abs(index - cardCount / 2f) * -12f).dp
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

@Composable
fun TopOpponentHand() {
    Row { repeat(4) { CardBack() } }
}

@Composable
fun SideOpponentHand() {
    Column { repeat(4) { CardBack() } }
}

@Composable
fun GameScreen(
    gameViewModel: GameViewModel = viewModel()
) {
    val gameState by gameViewModel.gameState.collectAsState()
    val players = gameState.players

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF006A4E))
    ) {
        if (players.size == 4) {
            val player = players[0]

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                PlayerHandFanned(cards = player.hand)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                TopOpponentHand()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                SideOpponentHand()
            }
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