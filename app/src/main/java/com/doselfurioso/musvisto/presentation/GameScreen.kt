package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.doselfurioso.musvisto.model.ButtonColorType
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.Player
import kotlin.math.cos
import kotlin.math.sin
import com.doselfurioso.musvisto.model.Card as CardData

// Custom modifier for the bottom border (no changes here)
@SuppressLint("UnnecessaryComposedModifier")
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
fun CardBack(modifier: Modifier = Modifier) { // <-- ADD THIS PARAMETER
    Image(
        painter = painterResource(id = R.drawable.card_back),
        contentDescription = "Card back",
        // Apply the passed-in modifier here, then add our specific ones
        modifier = modifier
            .height(80.dp)
            .aspectRatio(0.7f)
            .padding(vertical = 4.dp)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .bottomBorder(1.dp, Color.Black.copy(alpha = 0.5f))
    )
}


// --- The rest of the file (PlayerHandFanned, OpponentHand, GameScreen) remains the same ---
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun PlayerHandArc(cards: List<CardData>) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp), // Give the container a fixed height
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardCount = cards.size
        // Parameters to tweak the look of the arc
        val arcRadius = maxWidth.value * 1.5f
        val angleBetweenCards = 5.0f

        val totalAngle = (cardCount - 1) * angleBetweenCards
        val startAngle = -totalAngle / 2f

        cards.forEachIndexed { index, card ->
            val angle = startAngle + index * angleBetweenCards
            val angleRad = Math.toRadians(angle.toDouble())

            // We use trigonometry (sin and cos) to place cards in a perfect circle segment (an arc)
            val offsetX = (arcRadius * sin(angleRad)).dp
            val offsetY = (arcRadius * (1 - cos(angleRad))).dp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(x = offsetX, y = -offsetY) // Move the card to its position on the arc
                    .graphicsLayer {
                        rotationZ = angle // Rotate the card to follow the curve
                    }
            ) {
                GameCard(card = card)
            }
        }
    }
}

@Composable
fun PartnerHand() {
    Row { repeat(4) { CardBack() } }
}

// NEW: A dedicated Composable for a full player area (avatar + hand)
@Composable
fun PlayerArea(
    player: Player,
    isCurrentTurn: Boolean,
    handContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerAvatar(player = player, isCurrentTurn = isCurrentTurn)
        handContent()
    }
}

@Composable
fun SideOpponentHandStacked(cardCount: Int = 4) {
    Box {
        repeat(cardCount) { index ->
            Box(
                // Offset vertical para que se superpongan
                modifier = Modifier.offset(y = (index * 30).dp)
            ) {
                // Rotación individual para cada carta
                CardBack(modifier = Modifier.graphicsLayer { rotationZ = 90f })
            }
        }
    }
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
            val rivalLeft = players[1]
            val partner = players[2]
            val rivalRight = players[3]

            // --- JUGADOR PRINCIPAL (AVATAR + MANO AGRUPADOS) ---
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy((-40).dp) // Negative space to overlap
            ) {
                PlayerAvatar(
                    player = player,
                    isCurrentTurn = gameState.currentTurnPlayerId == player.id,
                    modifier = Modifier.padding(bottom = 20.dp) // Lift avatar slightly
                )
                PlayerHandArc(cards = player.hand)
            }

            // --- COMPAÑERO (AVATAR + MANO AGRUPADOS) ---
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(bottom = 16.dp, top = 32.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy((20).dp) // Negative space to overlap
            )    {
                PlayerAvatar(
                    player = partner,
                    isCurrentTurn = gameState.currentTurnPlayerId == partner.id
                )
                PartnerHand()
            }

            // --- RIVAL IZQUIERDO (AVATAR + MANO SEPARADOS PERO REPOSICIONADOS) ---



            // Rival Left Area (Center Start)
            PlayerArea(
                player = rivalLeft,
                isCurrentTurn = gameState.currentTurnPlayerId == rivalLeft.id,
                handContent = { SideOpponentHandStacked() },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, bottom = 240.dp)
            )

            // Rival Right Area (Center End)
            PlayerArea(
                player = rivalRight,
                isCurrentTurn = gameState.currentTurnPlayerId == rivalRight.id,
                handContent = { SideOpponentHandStacked() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp, bottom = 240.dp)
            )

            val currentPlayer = players.find { it.id == gameState.currentTurnPlayerId }

            // --- BOTONES DE ACCIÓN ---
            ActionButtons(
                actions = gameState.availableActions,
                onActionClick = { action ->
                    // We pass the current player's ID with the action
                    if (gameState.currentTurnPlayerId != null) {
                        gameViewModel.onAction(action, gameState.currentTurnPlayerId!!)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 240.dp, end = 16.dp),
                // The buttons are enabled only if the current player is human
                isEnabled = currentPlayer != null && !currentPlayer.isAi
            )
        }
    }
}

// NEW: A Composable to display a list of action buttons
@Composable
fun ActionButtons(
    actions: List<GameAction>,
    onActionClick: (GameAction) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        // NEW: Adds 8.dp of space between each button automatically
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { action ->
            // In ActionButtons composable
            val buttonColors = when (action.colorType) {
                ButtonColorType.PASS -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ButtonColorType.BET -> ButtonDefaults.buttonColors(containerColor = Color(0xFFBE8E00))
                ButtonColorType.CONFIRM -> ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)) // Green
                ButtonColorType.DENY -> ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)) // Red
                ButtonColorType.ULTIMATE -> ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)) // Purple
            }

            Button(
                onClick = { onActionClick(action) },
                colors = buttonColors, // Apply the determined colors
                enabled = isEnabled,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                // NEW: Add the icon to the button
                if (action.iconResId != null) {
                    Icon(
                        painter = painterResource(id = action.iconResId),
                        contentDescription = null, // The text describes the action
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing)) // Space between icon and text
                }
                Text(text = action.displayText)
            }
        }
    }
}

@Composable
fun PlayerAvatar(
    player: Player,
    isCurrentTurn: Boolean, // This will control the glow
    modifier: Modifier = Modifier
) {
    val borderColor = if (isCurrentTurn) {
        Color.Yellow // The color of the glow when it's their turn
    } else {
        Color.Transparent // No border when it's not their turn
    }

    Image(
        painter = painterResource(id = player.avatarResId),
        contentDescription = "Avatar of ${player.name}",
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape) // Make the avatar circular
            .border(4.dp, borderColor, CircleShape) // Apply the glowing border
    )
}