package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.BetInfo
import com.doselfurioso.musvisto.model.ButtonColorType
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
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
fun GameCard(
    card: CardData,
    isSelected: Boolean,
    onClick: () -> Unit,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    modifier: Modifier = Modifier
) {

    Image(
        painter = cardToPainter(card = card),
        contentDescription = "${card.rank} of ${card.suit}",
        modifier = modifier // <-- USAMOS EL MODIFIER QUE RECIBIMOS
            .width(80.dp)
            .aspectRatio(0.7f)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp), clip = false)
            .graphicsLayer{clip = if (isSelected) false else true
            translationY = if (isSelected) -80f else 0f}
            .clickable(
                enabled = (gamePhase == GamePhase.DISCARD), // Only enabled in discard phase
                onClick = { onClick() }
            )
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
            .bottomBorder(1.dp, Color.Black.copy(alpha = 0.5f))
    )
}


// --- The rest of the file (PlayerHandFanned, OpponentHand, GameScreen) remains the same ---
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun PlayerHandArc(
    cards: List<CardData>,
    selectedCards: Set<CardData>,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    onCardClick: (CardData) -> Unit
) {
    // Usamos un Box normal como contenedor. No necesita altura fija.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp), // Un margen inferior para toda la mano
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val isSelected = card in selectedCards

            // --- Cálculos de posición y rotación ---
            val centerOffset = index - (cardCount - 1) / 2f
            val rotation = centerOffset * 10f // Aumentamos un poco el ángulo
            val translationY = abs(centerOffset) * -15f // Curva parabólica para el arco
            val translationX = centerOffset * 150f // Espacio horizontal entre cartas

            GameCard(
                card = card,
                isSelected = isSelected,
                gamePhase = gamePhase,
                isMyTurn = isMyTurn,
                onClick = { onCardClick(card) },
                // Aplicamos todas las transformaciones en una única capa gráfica
                modifier = Modifier
                    .zIndex(if (isSelected) 1f else 0f)
                    .graphicsLayer {
                        // THE KEY CHANGE IS HERE:
                        this.rotationZ = if (isSelected) 0f else rotation
                        this.translationY = translationY
                        this.translationX = translationX
                        this.clip = false
                    }
            )
        }
    }
}

@Composable
fun PartnerHand() {
    Row { repeat(4) { CardBack() } }
}

//A dedicated Composable for a full player area (avatar + hand)
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
            val sortedHand = player.hand.sortedByDescending { it.rank.value }
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
                PlayerHandArc(
                    cards = sortedHand,
                    selectedCards = gameState.selectedCardsForDiscard,
                    gamePhase = gameState.gamePhase,
                    isMyTurn = gameState.currentTurnPlayerId == gameViewModel.humanPlayerId,
                    // When a card is clicked, the UI tells the ViewModel about it
                    onCardClick = { card -> gameViewModel.onCardSelected(card) }
                )
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

            // --- ELEMENTOS DE INFORMACIÓN DE LA PARTIDA ---

            // 1. Marcador de Puntuación
            Column (modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 128.dp)) {
                Scoreboard(
                    score = gameState.score,
                    modifier = Modifier
                        .padding(16.dp)
                )

                // 2. Indicador del Lance Actual
                LanceInfo(
                    gamePhase = gameState.gamePhase,
                    currentBet = gameState.currentBet,
                    players = players
                )
            }

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
                selectedCardCount = gameState.selectedCardsForDiscard.size,
                isEnabled = gameState.currentTurnPlayerId == gameViewModel.humanPlayerId

            )
        }
    }
}

//A Composable to display a list of action buttons
@Composable
fun ActionButtons(
    actions: List<GameAction>,
    onActionClick: (GameAction) -> Unit,
    modifier: Modifier = Modifier,
    selectedCardCount: Int,
    isEnabled: Boolean
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        //Adds 8.dp of space between each button automatically
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

            val isButtonEnabled = when (action) {
                // The discard button is enabled only if it's the player's turn AND cards are selected
                is GameAction.ConfirmDiscard -> isEnabled && selectedCardCount > 0
                // All other buttons are enabled only if it's the player's turn
                else -> isEnabled
            }

            Button(
                onClick = { onActionClick(action) },
                colors = buttonColors, // Apply the determined colors
                enabled = isButtonEnabled,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                //Add the icon to the button
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

@Composable
fun Scoreboard(score: Map<String, Int>, modifier: Modifier = Modifier) {
    // We use a Card for a nice background
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "NOSOTROS: ${score["teamA"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ELLOS: ${score["teamB"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}
@Composable
fun LanceInfo(
    gamePhase: GamePhase,
    currentBet: BetInfo?,
    players: List<Player>,
    modifier: Modifier = Modifier
) {
    val phaseText = gamePhase.name.replace('_', ' ') // Format the enum name nicely
    var betText = ""

    if (currentBet != null) {
        val playerName = players.find { it.id == currentBet.bettingPlayerId }?.name ?: ""
        betText = "\nEnvite de ${currentBet.amount} por $playerName"
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Text(
            text = phaseText + betText,
            color = Color.Yellow,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    }
}
