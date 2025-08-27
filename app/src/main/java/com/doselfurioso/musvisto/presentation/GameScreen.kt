package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.hilt.navigation.compose.hiltViewModel
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.ActionType
import com.doselfurioso.musvisto.model.BetInfo
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameEvent
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LanceResult
import com.doselfurioso.musvisto.model.LastActionInfo
import com.doselfurioso.musvisto.model.OrdagoInfo
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.ScoreBreakdown
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.doselfurioso.musvisto.model.Card as CardData

// --- 1. Definición de los Juegos de Dimensiones ---

data class GameScreenDimensions(
    val avatarSize: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val smallMargin: Dp,
    val mediumMargin: Dp,
    val largeMargin: Dp,
    val extraLargeMargin: Dp,
    val normalFontSize: TextUnit,
    val scoreFontSize: TextUnit,
    val lanceTrackerFontSize: TextUnit,
    val announcementIconSize: Dp,
    val buttonTextSize: TextUnit,
    val buttonIconSize: Dp,
    val playerHandCardOverlap: Dp,
    val sideOpponentStackOffset: Dp,
    val partnerHandRotation: Float,
    val sideOpponentCardRotation: Float,
    val playerHandArcRotationFactor: Float,
    val playerHandArcTranslationYFactor: Float,
    val playerHandArcTranslationXFactor: Float,
    val debugButtonSize: Dp,
    val cardBackPadding: Dp,
    val playerHandPaddingBottom: Dp,
    val scoreboardPadding: Dp,
    val lanceTrackerPaddingHorizontal: Dp,
    val lanceTrackerPaddingVertical: Dp,
    val roundEndOverlayPaddingBottom: Dp
)

val RegularDimensions = GameScreenDimensions(
    avatarSize = 80.dp,
    cardWidth = 70.dp,
    cardHeight = 98.dp, // Aspect ratio 0.7:1
    smallMargin = 4.dp,
    mediumMargin = 8.dp,
    largeMargin = 16.dp,
    extraLargeMargin = 32.dp,
    normalFontSize = 16.sp,
    scoreFontSize = 14.sp,
    lanceTrackerFontSize = 16.sp,
    announcementIconSize = 24.dp,
    buttonTextSize = 14.sp,
    buttonIconSize = 18.dp,
    playerHandCardOverlap = 150.dp, // Ajustar a tu preferencia para la superposición de cartas en arco
    sideOpponentStackOffset = 18.dp,
    partnerHandRotation = 180f,
    sideOpponentCardRotation = 270f,
    playerHandArcRotationFactor = 5f,
    playerHandArcTranslationYFactor = -15f,
    playerHandArcTranslationXFactor = 100f,
    debugButtonSize = 24.dp,
    cardBackPadding = 4.dp,
    playerHandPaddingBottom = 32.dp,
    scoreboardPadding = 16.dp,
    lanceTrackerPaddingHorizontal = 12.dp,
    lanceTrackerPaddingVertical = 8.dp,
    roundEndOverlayPaddingBottom = 120.dp
)

val CompactDimensions = GameScreenDimensions(
    avatarSize = 56.dp,
    cardWidth = 45.dp,
    cardHeight = 63.dp, // Aspect ratio 0.7:1
    smallMargin = 2.dp,
    mediumMargin = 4.dp,
    largeMargin = 8.dp,
    extraLargeMargin = 16.dp,
    normalFontSize = 12.sp,
    scoreFontSize = 10.sp,
    lanceTrackerFontSize = 12.sp,
    announcementIconSize = 18.dp,
    buttonTextSize = 12.sp,
    buttonIconSize = 16.dp,
    playerHandCardOverlap = 100.dp, // Reducir para compactar
    sideOpponentStackOffset = 12.dp,
    partnerHandRotation = 180f,
    sideOpponentCardRotation = 270f,
    playerHandArcRotationFactor = 7f, // Mayor rotación para compactar
    playerHandArcTranslationYFactor = -10f, // Menor elevación
    playerHandArcTranslationXFactor = 60f, // Menor expansión
    debugButtonSize = 20.dp,
    cardBackPadding = 2.dp,
    playerHandPaddingBottom = 24.dp,
    scoreboardPadding = 12.dp,
    lanceTrackerPaddingHorizontal = 8.dp,
    lanceTrackerPaddingVertical = 6.dp,
    roundEndOverlayPaddingBottom = 80.dp
)


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

//Composable for a single card
@Composable
fun GameCard(
    card: CardData,
    isSelected: Boolean,
    onClick: () -> Unit,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    dimensions: GameScreenDimensions, // Added dimensions
    modifier: Modifier = Modifier
) {
    Image(
        painter = cardToPainter(card = card),
        contentDescription = "${card.rank} of ${card.suit}",
        modifier = modifier
            .width(dimensions.cardWidth)
            .height(dimensions.cardHeight) // Use fixed height based on aspect ratio
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp), clip = false)
            .graphicsLayer {
                clip = if (isSelected) false else true
                translationY = if (isSelected) -dimensions.avatarSize.toPx() else 0f // Scale translation with avatar size
            }
            .clickable(
                enabled = (gamePhase == GamePhase.DISCARD && isMyTurn),
                onClick = { onClick() }
            )
    )
}

//Composable for the back of a card
@Composable
fun CardBack(dimensions: GameScreenDimensions, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.card_back),
        contentDescription = "Card back",
        modifier = modifier
            .width(dimensions.cardWidth)
            .height(dimensions.cardHeight) // Use fixed height
            .padding(vertical = dimensions.cardBackPadding)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp))
            .bottomBorder(1.dp, Color.Black.copy(alpha = 0.5f))
    )
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun PlayerHandArc(
    cards: List<CardData>,
    selectedCards: Set<CardData>,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    onCardClick: (CardData) -> Unit,
    dimensions: GameScreenDimensions // Added dimensions
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimensions.playerHandPaddingBottom),
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val isSelected = card in selectedCards

            val centerOffset = index - (cardCount - 1) / 2f
            val rotation = centerOffset * dimensions.playerHandArcRotationFactor
            val translationY = abs(centerOffset) * dimensions.playerHandArcTranslationYFactor
            val translationX = centerOffset * dimensions.playerHandArcTranslationXFactor

            GameCard(
                card = card,
                isSelected = isSelected,
                gamePhase = gamePhase,
                isMyTurn = isMyTurn,
                onClick = { onCardClick(card) },
                dimensions = dimensions,
                modifier = Modifier
                    .zIndex(if (isSelected) 1f else 0f)
                    .graphicsLayer {
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
fun PartnerHand(
    modifier: Modifier = Modifier,
    cards: List<CardData>,
    isDebugMode: Boolean,
    revealHand: Boolean,
    dimensions: GameScreenDimensions // Added dimensions
) {
    Row(modifier = modifier.graphicsLayer { rotationZ = dimensions.partnerHandRotation }) {
        cards.forEach { card ->
            if (isDebugMode || revealHand) {
                GameCard(
                    card = card,
                    isSelected = false,
                    gamePhase = GamePhase.PRE_GAME,
                    isMyTurn = false,
                    onClick = {},
                    dimensions = dimensions
                )
            } else {
                CardBack(dimensions = dimensions)
            }
        }
    }
}

@Composable
fun SideOpponentHandStacked(
    cards: List<CardData>,
    isDebugMode: Boolean,
    revealHand: Boolean,
    rotate: Boolean = false,
    dimensions: GameScreenDimensions // Added dimensions
) {
    Box {
        repeat(cards.size) { index ->
            Box(
                modifier = Modifier.offset(y = (dimensions.sideOpponentStackOffset))
            ) {
                if (isDebugMode || revealHand) {
                    GameCard(
                        card = cards[index],
                        isSelected = false,
                        gamePhase = GamePhase.PRE_GAME,
                        isMyTurn = false,
                        onClick = {},
                        dimensions = dimensions,
                        modifier = Modifier.graphicsLayer { if (rotate) rotationZ = 90f else rotationZ = dimensions.sideOpponentCardRotation }
                    )
                } else {
                    CardBack(dimensions = dimensions, modifier = Modifier.graphicsLayer { rotationZ = dimensions.sideOpponentCardRotation })
                }
            }
        }
    }
}


@Composable
fun VerticalPlayerArea(
    player: Player,
    isCurrentTurn: Boolean,
    isMano: Boolean,
    hasCutMus: Boolean,
    dimensions: GameScreenDimensions, // Added dimensions
    handContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimensions.mediumMargin)
    ) {
        PlayerAvatar(
            player = player,
            isCurrentTurn = isCurrentTurn,
            isMano = isMano,
            hasCutMus = hasCutMus,
            dimensions = dimensions
        )
        handContent()
    }
}

@Composable
fun HorizontalPlayerArea(
    player: Player,
    isCurrentTurn: Boolean,
    isMano: Boolean,
    hasCutMus: Boolean,
    dimensions: GameScreenDimensions, // Added dimensions
    handContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.mediumMargin)
    ) {
        PlayerAvatar(
            player = player,
            isCurrentTurn = isCurrentTurn,
            isMano = isMano,
            hasCutMus = hasCutMus,
            dimensions = dimensions
        )
        Box(contentAlignment = Alignment.Center) {
            handContent()
        }
    }
}

/**
 * Stateful Composable: Obtiene el ViewModel y maneja el estado.
 * Esta es la función que se llama desde la MainActivity.
 */
@Composable
fun GameScreen(
    gameViewModel: GameViewModel = hiltViewModel()
) {
    val gameState by gameViewModel.gameState.collectAsState()
    val isDebugMode by gameViewModel.isDebugMode.collectAsState()

    StatelessGameScreen(
        gameState = gameState,
        isDebugMode = isDebugMode,
        humanPlayerId = gameViewModel.humanPlayerId,
        onAction = { action, playerId -> gameViewModel.onAction(action, playerId) },
        onCardSelected = { card -> gameViewModel.onCardSelected(card) },
        onToggleDebugMode = { gameViewModel.onToggleDebugMode() }
    )
}

/**
 * Stateless Composable: Contiene toda la lógica de la UI y es fácilmente previsualizable.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun StatelessGameScreen(
    gameState: GameState,
    isDebugMode: Boolean,
    humanPlayerId: String,
    onAction: (GameAction, String) -> Unit,
    onCardSelected: (CardData) -> Unit,
    onToggleDebugMode: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimensions = if (maxWidth < 380.dp) CompactDimensions else RegularDimensions

        Box(modifier = Modifier.fillMaxSize()) {
            if (gameState.players.size == 4) {
                val player = gameState.players.first { it.id == humanPlayerId }
                val partner = gameState.players.first { it.team == player.team && it.id != player.id }
                val rivals = gameState.players.filter { it.team != player.team }
                val rivalLeft = rivals.getOrNull(0) ?: Player("", "Rival Izq", emptyList(), R.drawable.avatar_navarra, true, "teamB")
                val rivalRight = rivals.getOrNull(1) ?: Player("", "Rival Der", emptyList(), R.drawable.avatar_granada, true, "teamB")

                val isMyTurn = gameState.currentTurnPlayerId == humanPlayerId

                ConstraintLayout(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF006A4E))
                ) {
                    val (
                        partnerArea, rivalLeftArea, rivalRightArea, playerArea,
                        centerInfo, actionButtons, debugButton,
                        announcementLeft, announcementRight, announcementPartner, announcementPlayer
                    ) = createRefs()

                    // ÁREA DEL COMPAÑERO (ARRIBA)
                    HorizontalPlayerArea(
                        modifier = Modifier.constrainAs(partnerArea) {
                            top.linkTo(parent.top, margin = dimensions.largeMargin)
                            centerHorizontallyTo(parent)
                        },
                        player = partner,
                        isCurrentTurn = gameState.currentTurnPlayerId == partner.id,
                        isMano = gameState.manoPlayerId == partner.id,
                        hasCutMus = gameState.noMusPlayer == partner.id,
                        dimensions = dimensions,
                        handContent = {
                            PartnerHand(
                                cards = partner.hand.sortedByDescending { it.rank.value },
                                isDebugMode = isDebugMode,
                                revealHand = gameState.revealAllHands,
                                dimensions = dimensions
                            )
                        }
                    )

                    // ÁREA DEL RIVAL IZQUIERDO (IZQUIERDA)
                    VerticalPlayerArea(
                        modifier = Modifier.constrainAs(rivalLeftArea) {
                            start.linkTo(parent.start, margin = dimensions.mediumMargin)
                            centerVerticallyTo(parent, bias = 0.35f)
                        },
                        player = rivalLeft,
                        isCurrentTurn = gameState.currentTurnPlayerId == rivalLeft.id,
                        isMano = gameState.manoPlayerId == rivalLeft.id,
                        hasCutMus = gameState.noMusPlayer == rivalLeft.id,
                        dimensions = dimensions,
                        handContent = {
                            SideOpponentHandStacked(
                                cards = rivalLeft.hand.sortedByDescending { it.rank.value },
                                isDebugMode = isDebugMode,
                                revealHand = gameState.revealAllHands,
                                rotate = true,
                                dimensions = dimensions
                            )
                        }
                    )

                    // ÁREA DEL RIVAL DERECHO (DERECHA)
                    VerticalPlayerArea(
                        modifier = Modifier.constrainAs(rivalRightArea) {
                            end.linkTo(parent.end, margin = dimensions.mediumMargin)
                            centerVerticallyTo(parent, bias = 0.35f)
                        },
                        player = rivalRight,
                        isCurrentTurn = gameState.currentTurnPlayerId == rivalRight.id,
                        isMano = gameState.manoPlayerId == rivalRight.id,
                        hasCutMus = gameState.noMusPlayer == rivalRight.id,
                        dimensions = dimensions,
                        handContent = {
                            SideOpponentHandStacked(
                                cards = rivalRight.hand.sortedBy { it.rank.value },
                                isDebugMode = isDebugMode,
                                revealHand = gameState.revealAllHands,
                                rotate = false,
                                dimensions = dimensions
                            )
                        }
                    )

                    // ÁREA DEL JUGADOR PRINCIPAL (ABAJO)
                    Box(
                        modifier = Modifier.constrainAs(playerArea) {
                            bottom.linkTo(parent.bottom, margin = dimensions.mediumMargin)
                            centerHorizontallyTo(parent)
                            width = Dimension.fillToConstraints
                        }
                    ) {
                        HorizontalPlayerArea(
                            player = player,
                            isCurrentTurn = isMyTurn,
                            isMano = gameState.manoPlayerId == player.id,
                            hasCutMus = gameState.noMusPlayer == player.id,
                            dimensions = dimensions,
                            handContent = {
                                PlayerHandArc(
                                    cards = player.hand.sortedByDescending { it.rank.value },
                                    selectedCards = gameState.selectedCardsForDiscard,
                                    gamePhase = gameState.gamePhase,
                                    isMyTurn = isMyTurn,
                                    onCardClick = { card -> onCardSelected(card) },
                                    dimensions = dimensions
                                )
                            }
                        )
                    }

                    // BOTONES DE ACCIÓN
                    ActionButtons(
                        modifier = Modifier.constrainAs(actionButtons) {
                            bottom.linkTo(playerArea.top, margin = dimensions.extraLargeMargin)
                            end.linkTo(parent.end, margin = dimensions.mediumMargin)
                            start.linkTo(parent.start, margin = dimensions.mediumMargin)
                            width = Dimension.fillToConstraints
                        },
                        actions = gameState.availableActions,
                        gamePhase = gameState.gamePhase,
                        onActionClick = { action, playerId -> onAction(action, playerId) },
                        selectedCardCount = gameState.selectedCardsForDiscard.size,
                        isEnabled = isMyTurn,
                        currentPlayerId = humanPlayerId,
                        dimensions = dimensions
                    )

                    // INFO CENTRAL (MARCADOR Y LANCES)
                    Column(
                        modifier = Modifier.constrainAs(centerInfo) {
                            centerTo(parent)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dimensions.mediumMargin)
                    ) {
                        Scoreboard(score = gameState.score, dimensions = dimensions)
                        LanceTracker(
                            currentPhase = gameState.gamePhase,
                            history = gameState.roundHistory,
                            currentBet = gameState.currentBet,
                            isPuntoPhase = gameState.isPuntoPhase,
                            dimensions = dimensions
                        )
                    }

                    // ANUNCIOS DE ACCIÓN (Con posicionamiento de precisión)
                    Box(modifier = Modifier.constrainAs(announcementLeft) {
                        start.linkTo(rivalLeftArea.end, margin = dimensions.smallMargin)
                        top.linkTo(rivalLeftArea.top)
                        bottom.linkTo(rivalLeftArea.bottom)
                    }) { ActionAnnouncement(rivalLeft, gameState, dimensions) }

                    Box(modifier = Modifier.constrainAs(announcementRight) {
                        end.linkTo(rivalRightArea.start, margin = dimensions.smallMargin)
                        top.linkTo(rivalRightArea.top)
                        bottom.linkTo(rivalRightArea.bottom)
                    }) { ActionAnnouncement(rivalRight, gameState, dimensions) }

                    Box(modifier = Modifier.constrainAs(announcementPartner) {
                        top.linkTo(partnerArea.bottom)
                        centerHorizontallyTo(partnerArea)
                    }.offset(y = -dimensions.mediumMargin)) { ActionAnnouncement(partner, gameState, dimensions) }

                    Box(modifier = Modifier.constrainAs(announcementPlayer) {
                        bottom.linkTo(actionButtons.top, margin = dimensions.smallMargin)
                        centerHorizontallyTo(playerArea)
                    }) { ActionAnnouncement(player, gameState, dimensions) }


                    // BOTÓN DE DEBUG
                    IconButton(
                        onClick = onToggleDebugMode,
                        modifier = Modifier
                            .constrainAs(debugButton) {
                                top.linkTo(parent.top, margin = dimensions.largeMargin)
                                end.linkTo(parent.end, margin = dimensions.largeMargin)
                            }
                            .size(dimensions.debugButtonSize)
                            .zIndex(3f)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_debug),
                            contentDescription = "Toggle Debug Mode",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(dimensions.debugButtonSize)
                        )
                    }
                }

                // --- Capas superpuestas (Overlays) ---

                if (gameState.isSelectingBet) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        BetSelector(
                            onBet = { amount -> onAction(GameAction.Envido(amount), humanPlayerId) },
                            onCancel = { onAction(GameAction.CancelBetSelection, humanPlayerId) },
                            dimensions = dimensions
                        )
                    }
                }

                Box(
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    GameEventNotification(event = gameState.event, dimensions = dimensions)
                }

                if (gameState.gamePhase == GamePhase.ROUND_OVER && gameState.scoreBreakdown != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable(enabled = false, onClick = {}),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        RoundEndOverlay(
                            breakdown = gameState.scoreBreakdown!!,
                            onContinueClick = { onAction(GameAction.Continue, humanPlayerId) },
                            modifier = Modifier.padding(bottom = dimensions.roundEndOverlayPaddingBottom),
                            dimensions = dimensions
                        )
                    }
                }

                if (gameState.gamePhase == GamePhase.GAME_OVER && gameState.winningTeam != null) {
                    GameOverOverlay(
                        winnerTeam = gameState.winningTeam!!,
                        ordagoInfo = gameState.ordagoInfo,
                        players = gameState.players,
                        onNewGameClick = { onAction(GameAction.NewGame, humanPlayerId) },
                        dimensions = dimensions
                    )
                }
            }
        }
    }
}


@Composable
fun DiscardCountIndicator(count: Int, dimensions: GameScreenDimensions, modifier: Modifier = Modifier) {
    if (count > 0) {
        Text(
            text = "Descarta: $count",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = dimensions.normalFontSize,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(dimensions.smallMargin))
                .padding(horizontal = dimensions.mediumMargin, vertical = dimensions.smallMargin)
        )
    }
}

//A Composable to display a list of action buttons
@Composable
private fun GameActionButton(
    action: GameAction,
    onClick: () -> Unit,
    isEnabled: Boolean,
    dimensions: GameScreenDimensions // Added dimensions
) {
    val buttonColors = when (action.actionType) {
        ActionType.PASS -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ActionType.BET -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEB3B))
        ActionType.CONFIRM, ActionType.DISCARD  -> ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ActionType.DENY -> ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
        ActionType.ULTIMATE -> ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
    }

    val secondaryColor = Color.Black

    Button(
        onClick = onClick,
        colors = buttonColors,
        enabled = isEnabled,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = dimensions.smallMargin)
    ) {
        if (action.iconResId != null) {

            if (action.actionType == ActionType.BET && isEnabled) Icon(
                painter = painterResource(id = action.iconResId),
                contentDescription = null,
                modifier = Modifier.size(dimensions.buttonIconSize),
                tint = secondaryColor
            ) else Icon(
                painter = painterResource(id = action.iconResId),
                contentDescription = null,
                modifier = Modifier.size(dimensions.buttonIconSize))

            Spacer(Modifier.size(dimensions.smallMargin))
        }

        if (action.actionType == ActionType.BET && isEnabled) {
            Text(text = action.displayText, color = secondaryColor, fontSize = dimensions.buttonTextSize)
        } else if (action.actionType == ActionType.DISCARD) {
            Text(text = "Descartar", fontSize = dimensions.buttonTextSize)
        } else {
            Text(text = action.displayText, fontSize = dimensions.buttonTextSize)
        }
    }
}


@Composable
fun ActionButtons(
    actions: List<GameAction>,
    gamePhase: GamePhase, // <-- Necesitamos saber la fase actual
    onActionClick: (GameAction, String) -> Unit,
    selectedCardCount: Int,
    isEnabled: Boolean,
    currentPlayerId: String,
    dimensions: GameScreenDimensions, // Added dimensions
    modifier: Modifier = Modifier
) {
    val availableActionsMap = actions.associateBy {
        if (it is GameAction.Envido) GameAction.Envido::class else it::class
    }

    // --- Layout principal que cambia según la fase del juego ---
    Box(modifier = modifier,
        contentAlignment = Alignment.CenterEnd) { // Alineamos los botones a la derecha del Box
        when (gamePhase) {
            GamePhase.MUS -> {
                // FASE MUS: Solo se muestran los botones de Mus
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimensions.largeMargin, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GameActionButton(
                        action = GameAction.Mus,
                        onClick = { onActionClick(GameAction.Mus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Mus::class),
                        dimensions = dimensions
                    )
                    GameActionButton(
                        action = GameAction.NoMus,
                        onClick = { onActionClick(GameAction.NoMus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.NoMus::class),
                        dimensions = dimensions
                    )
                }
            }
            GamePhase.DISCARD -> {
                // FASE DESCARTE: Solo se muestra el botón de descartar
                val discardAction = GameAction.ConfirmDiscard
                GameActionButton(
                    action = discardAction,
                    onClick = { onActionClick(discardAction, currentPlayerId) },
                    isEnabled = isEnabled && selectedCardCount > 0,
                    dimensions = dimensions
                )
            }
            else -> {
                // FASES DE APUESTA (GRANDE, CHICA, PARES, JUEGO)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimensions.mediumMargin, Alignment.End), // Alineamos a la derecha
                    verticalAlignment = Alignment.CenterVertically
                )  {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(dimensions.mediumMargin),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val quieroAction = GameAction.Quiero
                        GameActionButton(
                            action = quieroAction,
                            onClick = { onActionClick(quieroAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(quieroAction::class),
                            dimensions = dimensions
                        )
                        val noQuieroAction = GameAction.NoQuiero
                        GameActionButton(
                            action = noQuieroAction,
                            onClick = { onActionClick(noQuieroAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(noQuieroAction::class),
                            dimensions = dimensions
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(dimensions.mediumMargin),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pasoAction = GameAction.Paso
                        GameActionButton(
                            action = pasoAction,
                            onClick = { onActionClick(pasoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(pasoAction::class),
                            dimensions = dimensions
                        )
                        val envidoAction = GameAction.ToggleBetSelector
                        GameActionButton(
                            action = envidoAction,
                            onClick = { onActionClick(envidoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Envido::class),
                            dimensions = dimensions
                        )
                        val ordagoAction = GameAction.Órdago
                        GameActionButton(
                            action = ordagoAction,
                            onClick = { onActionClick(ordagoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(ordagoAction::class),
                            dimensions = dimensions
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerAvatar(
    player: Player,
    isCurrentTurn: Boolean,
    modifier: Modifier = Modifier,
    isMano: Boolean,
    hasCutMus: Boolean,
    dimensions: GameScreenDimensions // Added dimensions
) {
    val borderColor = if (isCurrentTurn) Color.Yellow else Color.Transparent

    Box(
        modifier = modifier.size(dimensions.avatarSize),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = player.avatarResId),
            contentDescription = "Avatar of ${player.name}",
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(dimensions.smallMargin, borderColor, CircleShape) // Use smallMargin for border
        )

        if (isMano) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mano),
                contentDescription = "Indicador de Mano",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dimensions.announcementIconSize)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(dimensions.smallMargin)
            )
        }

        if (hasCutMus) {
            Icon(
                painter = painterResource(id = R.drawable.ic_cut),
                contentDescription = "Indicador de Corta Mus",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(dimensions.announcementIconSize)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(dimensions.smallMargin)
            )
        }
    }
}

@Composable
fun Scoreboard(score: Map<String, Int>, dimensions: GameScreenDimensions, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(dimensions.scoreboardPadding)) {
            Text(
                text = "NOSOTROS: ${score["teamA"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimensions.scoreFontSize
            )
            Spacer(modifier = Modifier.height(dimensions.mediumMargin))
            Text(
                text = "ELLOS: ${score["teamB"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimensions.scoreFontSize
            )
        }
    }
}

@Composable
fun ActionAnnouncement(
    player: Player,
    gameState: GameState,
    dimensions: GameScreenDimensions // Added dimensions
) {
    var actionToShow = gameState.currentLanceActions[player.id]

    if (actionToShow == null && gameState.transientAction?.playerId == player.id) {
        actionToShow = gameState.transientAction
    }
    val visible = actionToShow != null

    if (gameState.transientAction?.playerId == player.id) {
        actionToShow = gameState.transientAction
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier.padding(dimensions.mediumMargin)
        ) {
            val text = if (actionToShow?.action is GameAction.ConfirmDiscard) {
                ("Dame ${gameState.discardCounts[player.id]}")
            } else {
                actionToShow?.action?.displayText ?: ""
            }
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimensions.normalFontSize,
                modifier = Modifier.padding(horizontal = dimensions.largeMargin, vertical = dimensions.mediumMargin)
            )
        }
    }
}


@Composable
fun GameOverOverlay(
    winnerTeam: String,
    ordagoInfo: OrdagoInfo?,
    players: List<Player>,
    onNewGameClick: () -> Unit,
    dimensions: GameScreenDimensions // Added dimensions
) {
    val titleText: String
    val subtitleText: String
    if (ordagoInfo != null) {
        val winnerPlayer = players.find { it.id == ordagoInfo.winnerId }
        titleText = "¡VICTORIA POR ÓRDAGO!"
        subtitleText = "Gana el equipo de ${winnerPlayer?.name ?: ""} en ${ordagoInfo.lance.name}"
    } else {
        titleText = if (winnerTeam == "teamA") "¡HAS GANADO!" else "HAS PERDIDO"
        subtitleText = "La partida ha terminado"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimensions.largeMargin)
        ) {
            Text(text = titleText, color = if (ordagoInfo != null) Color.Red else Color.Yellow, fontSize = dimensions.normalFontSize)
            Text(text = subtitleText, color = Color.White, fontSize = dimensions.normalFontSize)
            Button(onClick = onNewGameClick) {
                Text(text = "Jugar de Nuevo", fontSize = dimensions.normalFontSize)
            }
        }
    }
}

@Composable
fun GameEventNotification(event: GameEvent?, dimensions: GameScreenDimensions) {
    var visible by remember(event) { mutableStateOf(event != null) }

    LaunchedEffect(event) {
        if (event != null) {
            delay(3000)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
    ) {
        val text = when (event) {
            GameEvent.DISCARD_PILE_SHUFFLED -> "¡No quedan cartas! Barajando descartes..."
            null -> ""
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0)),
            elevation = CardDefaults.cardElevation(defaultElevation = dimensions.mediumMargin)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimensions.normalFontSize,
                modifier = Modifier.padding(dimensions.largeMargin)
            )
        }
    }
}

@Composable
fun RoundEndOverlay(
    breakdown: ScoreBreakdown,
    onContinueClick: () -> Unit,
    dimensions: GameScreenDimensions, // Added dimensions
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = dimensions.mediumMargin),
        border = BorderStroke(1.dp, Color.Yellow.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E).copy(alpha = 0.9f))
    ) {
        Column(
            modifier = Modifier.padding(dimensions.largeMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimensions.mediumMargin)
        ) {
            Text("FIN DE LA RONDA", fontSize = dimensions.normalFontSize, fontWeight = FontWeight.Bold, color = Color.Yellow)

            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensions.largeMargin),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOSOTROS", fontSize = dimensions.normalFontSize, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(dimensions.smallMargin))
                    breakdown.teamAScoreDetails.forEach { detail ->
                        Row(modifier = Modifier.width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(detail.reason, color = Color.LightGray, fontSize = dimensions.scoreFontSize)
                            Text("+${detail.points}", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(dimensions.smallMargin))
                    Box(modifier = Modifier.height(1.dp).width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f).background(Color.Gray))
                    Row(modifier = Modifier.width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Ronda", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("+${breakdown.teamAScoreDetails.sumOf { it.points }}", color = Color.Yellow, fontWeight = FontWeight.Bold)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ELLOS", fontSize = dimensions.normalFontSize, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(dimensions.smallMargin))
                    breakdown.teamBScoreDetails.forEach { detail ->
                        Row(modifier = Modifier.width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(detail.reason, color = Color.LightGray, fontSize = dimensions.scoreFontSize)
                            Text("+${detail.points}", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(dimensions.smallMargin))
                    Box(modifier = Modifier.height(1.dp).width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f).background(Color.Gray))
                    Row(modifier = Modifier.width(150.dp * if (dimensions == CompactDimensions) 0.8f else 1f), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Ronda", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("+${breakdown.teamBScoreDetails.sumOf { it.points }}", color = Color.Yellow, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Button(onClick = onContinueClick) {
                Text("Continuar", fontSize = dimensions.buttonTextSize)
            }
        }
    }
}

@Composable
fun LanceTracker(
    currentPhase: GamePhase,
    history: List<LanceResult>,
    isPuntoPhase: Boolean,
    currentBet: BetInfo?,
    dimensions: GameScreenDimensions, // Added dimensions
    modifier: Modifier = Modifier
) {
    val lances = listOf(GamePhase.GRANDE, GamePhase.CHICA, GamePhase.PARES, GamePhase.JUEGO)

    Card(
        modifier = modifier.width(IntrinsicSize.Max),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = dimensions.lanceTrackerPaddingHorizontal, vertical = dimensions.lanceTrackerPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(dimensions.smallMargin)
        ) {
            lances.forEach { lance ->
                val isCurrent = (currentPhase == lance)
                val result = history.find { it.lance == lance }
                val wasSkipped = result?.outcome == "Skipped"
                var resultText = ""
                if (isCurrent && currentBet != null) {
                    resultText = "En juego: ${currentBet.amount}"
                } else if (result != null && !wasSkipped) {
                    resultText = when (result.outcome) {
                        "Querido" -> "Vale ${result.amount}"
                        "No Querido" -> "No Querida"
                        "Paso" -> "En Paso"
                        else -> ""
                    }
                }

                val lanceName = if (lance == GamePhase.JUEGO && isPuntoPhase) "PUNTO" else lance.name
                val textColor = when {
                    isCurrent -> Color.Yellow
                    wasSkipped && lance == GamePhase.PARES -> Color.DarkGray
                    else -> Color.White
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = lanceName, color = textColor, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, fontSize = dimensions.lanceTrackerFontSize)
                    if (resultText.isNotEmpty()) {
                        Text(text = resultText, color = Color.Gray, fontSize = dimensions.scoreFontSize)
                    }
                }
            }
        }
    }
}

@Composable
fun BetSelector(
    onBet: (Int) -> Unit,
    onCancel: () -> Unit,
    dimensions: GameScreenDimensions // Added dimensions
) {
    var betAmount by remember { mutableStateOf(2) }

    Card(
        modifier = Modifier.fillMaxWidth(0.8f),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.9f)),
        elevation = CardDefaults.cardElevation(defaultElevation = dimensions.mediumMargin),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(dimensions.largeMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimensions.largeMargin)
        ) {
            Text("¿Cuántos quieres envidar?", color = Color.White, fontSize = dimensions.normalFontSize)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensions.largeMargin)
            ) {
                Button(onClick = { if (betAmount > 2) betAmount-- }) { Text("-", fontSize = dimensions.normalFontSize) }
                Text(betAmount.toString(), color = Color.Yellow, fontSize = dimensions.normalFontSize, fontWeight = FontWeight.Bold)
                Button(onClick = { betAmount++ }) { Text("+", fontSize = dimensions.normalFontSize) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimensions.largeMargin)) {
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("Cancelar", fontSize = dimensions.buttonTextSize) }
                Button(onClick = { onBet(betAmount) }) { Text("¡Envidar!", fontSize = dimensions.buttonTextSize) }
            }
        }
    }
}


// --- PREVIEW ---

