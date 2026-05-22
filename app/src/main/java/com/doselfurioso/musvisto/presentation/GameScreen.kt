package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.ActionType
import com.doselfurioso.musvisto.model.ActiveGestureInfo
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
import com.doselfurioso.musvisto.model.ScoreDetail
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.doselfurioso.musvisto.debug.DebugFeatures
import com.doselfurioso.musvisto.model.Card as CardData

internal const val ANNOUNCEMENT_MIN_VISIBLE_MS = 1200L
// Piso corto cuando el anuncio es REEMPLAZADO por otra acción (no ocultado):
// basta un golpe de vista + cross-fade. Evita que la acción anterior del propio
// jugador se quede colgada cuando actúa muy rápido (p. ej. No Mus -> Paso).
private const val ANNOUNCEMENT_MIN_BEFORE_REPLACE_MS = 450L
private const val ANNOUNCEMENT_ENTER_MS = 200
private const val ANNOUNCEMENT_EXIT_MS = 250
private const val ANNOUNCEMENT_TEXT_FADE_MS = 180

// #15: alfa del avatar de quien NO juega el lance actual (PARES/JUEGO sin
// pares/juego). Atenuado, no oculto: sigue ahí pero claramente fuera.
private const val DIMMED_AVATAR_ALPHA = 0.4f

// Tope visual del selector de envite. En el Mus no hay límite real de
// cuánto se puede envidar; lo acotamos al valor máximo de un juego (40 =
// órdago / puntos para ganar). Evita envidar cantidades arbitrarias con "+".
private const val MAX_BET = 40

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


@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GameScreen(
    gameViewModel: GameViewModel,
    navController: NavController
) {
    val gameState by gameViewModel.gameState.collectAsState()
    val isDebugMode by gameViewModel.isDebugMode.collectAsState()
    val players = gameState.players

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF006A4E))
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val minScreenWidth = 320.dp
        val maxScreenWidth = 800.dp
        val minScale = 0.70f
        val maxScale = 1.25f
        val widthProgress = ((screenWidth - minScreenWidth) / (maxScreenWidth - minScreenWidth)).coerceIn(0f, 1f)
        val widthScale = minScale + widthProgress * (maxScale - minScale)

        val minScreenHeight = 560.dp
        val maxScreenHeight = 1000.dp
        val heightProgress = ((screenHeight - minScreenHeight) / (maxScreenHeight - minScreenHeight)).coerceIn(0f, 1f)
        val heightScale = minScale + heightProgress * (maxScale - minScale)

        val scaleFactor = minOf(widthScale, heightScale)

        val dimens = remember(scaleFactor) {
            ResponsiveDimens(
                cardWidth           = (114.dp * scaleFactor).coerceIn(46.dp, 114.dp),
                cardBackWidth       = (72.dp * scaleFactor).coerceIn(36.dp, 85.dp),
                cardAspectRatio     = 0.7f,
                avatarSize          = (90.dp * scaleFactor).coerceIn(42.dp, 115.dp),
                handArcTranslationX = 150f * scaleFactor,
                handArcTranslationY = 15f * scaleFactor,
                handArcRotation     = 5f * scaleFactor,
                largePadding        = (48.dp * scaleFactor).coerceIn(12.dp, 88.dp),
                defaultPadding      = (12.dp * scaleFactor).coerceIn(6.dp, 24.dp),
                smallPadding        = (6.dp * scaleFactor).coerceIn(3.dp, 10.dp),
                actionButtonsPadding = (60.dp * scaleFactor),
                fontSizeLarge       = (20.sp * scaleFactor).let { if (it.value < 12f) 12.sp else if (it.value > 22f) 22.sp else it },
                fontSizeMedium      = (16.sp * scaleFactor).let { if (it.value < 10f) 10.sp else if (it.value > 17f) 17.sp else it },
                fontSizeSmall       = (10.sp * scaleFactor).let { if (it.value <  8f)  8.sp else if (it.value > 13f) 13.sp else it },
                buttonVPadding      = (5.dp * scaleFactor).coerceIn(3.dp, 10.dp),
                buttonHPadding      = (5.dp * scaleFactor).coerceIn(3.dp, 10.dp),
                scaleFactor         = scaleFactor
            )
        }

        if (gameState.players.size == 4) {
            val player     = players[0]
            val rivalLeft  = players[1]
            val partner    = players[2]
            val rivalRight = players[3]

            val isMyTurn = gameState.currentTurnPlayerId == gameViewModel.humanPlayerId
            // (Se eliminó `actionsForUi`/`currentPlayer`: cómputo muerto —
            // ActionButtons usa `gameState.availableActions` directamente.)

            // Mus corrido (#17): el icono de mano sigue al jugador que decide
            // AHORA (el que "tiene el mazo"), no a la mano fija — así se ve el
            // mus corriendo a la derecha. Quien corta se queda de mano ahí.
            // Fuera de mus corrido, es el mano normal.
            val displayedManoId = if (gameState.musCorrido)
                gameState.currentTurnPlayerId else gameState.manoPlayerId

            // ── CAPA 1: el layout real (Column con pesos) ──
            Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {

                // FILA 1 — Compañero
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                        .padding(top = dimens.defaultPadding * 2),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalPlayerArea(
                        player = partner,
                        isCurrentTurn = gameState.currentTurnPlayerId == partner.id,
                        isMano = displayedManoId == partner.id,
                        hasCutMus = gameState.noMusPlayer == partner.id,
                        activeGesture = gameState.activeGesture,
                        handContent = {
                            PartnerHand(
                                modifier = Modifier.graphicsLayer { rotationZ = 180f },
                                cards = partner.hand.sortedByDescending { it.rank.value },
                                isDebugMode = isDebugMode,
                                revealHand = gameState.revealAllHands,
                                dimens = dimens
                            )
                        },
                        dimens = dimens,
                        gameState = gameState,
                        announcementAbove = false
                    )
                }

                // FILA 2 — Rivales laterales + centro
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3.5f),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier.weight(1.5f).fillMaxHeight().padding(top = dimens.largePadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        VerticalPlayerArea(
                            player = rivalLeft,
                            isCurrentTurn = gameState.currentTurnPlayerId == rivalLeft.id,
                            isMano = displayedManoId == rivalLeft.id,
                            hasCutMus = gameState.noMusPlayer == rivalLeft.id,
                            activeGesture = gameState.activeGesture,
                            handContent = {
                                SideOpponentHandStacked(
                                    modifier = Modifier.graphicsLayer { rotationX = 90f },
                                    cards = rivalLeft.hand.sortedByDescending { it.rank.value },
                                    isDebugMode = isDebugMode,
                                    revealHand = gameState.revealAllHands,
                                    rotate = true,
                                    dimens = dimens
                                )
                            },
                            dimens = dimens,
                            gameState = gameState
                        )
                    }

                    Column(
                        modifier = Modifier.weight(2f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Scoreboard(score = gameState.score, dimens = dimens)
                        Spacer(modifier = Modifier.height(6.dp))
                        LanceTracker(
                            currentPhase = gameState.gamePhase,
                            history = gameState.roundHistory,
                            isPuntoPhase = gameState.isPuntoPhase,
                            dimens = dimens,
                            currentBet = gameState.currentBet
                        )
                    }

                    Box(
                        modifier = Modifier.weight(1.5f).fillMaxHeight().padding(top = dimens.largePadding),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        VerticalPlayerArea(
                            player = rivalRight,
                            isCurrentTurn = gameState.currentTurnPlayerId == rivalRight.id,
                            isMano = displayedManoId == rivalRight.id,
                            hasCutMus = gameState.noMusPlayer == rivalRight.id,
                            activeGesture = gameState.activeGesture,
                            handContent = {
                                SideOpponentHandStacked(
                                    modifier = Modifier.graphicsLayer { rotationZ = 180f },
                                    cards = rivalRight.hand.sortedBy { it.rank.value },
                                    isDebugMode = isDebugMode,
                                    revealHand = gameState.revealAllHands,
                                    rotate = false,
                                    dimens = dimens
                                )
                            },
                            dimens = dimens,
                            gameState = gameState
                        )
                    }
                }

                // FILA 3 — Jugador principal + botones de acción superpuestos
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(4.5f)
                        .padding(top = dimens.defaultPadding),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // Mano del jugador en la parte inferior
                    HorizontalPlayerArea(
                        player = player,
                        isCurrentTurn = isMyTurn,
                        isMano = displayedManoId == player.id,
                        hasCutMus = gameState.noMusPlayer == player.id,
                        activeGesture = gameState.activeGesture,
                        handContent = {
                            PlayerHandArc(
                                cards = player.hand.sortedByDescending { it.rank.value },
                                selectedCards = gameState.selectedCardsForDiscard,
                                gamePhase = gameState.gamePhase,
                                isMyTurn = isMyTurn,
                                onCardClick = { card -> gameViewModel.onCardSelected(card) },
                                dimens = dimens
                            )
                        },
                        dimens = dimens,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        gameState = gameState,
                        announcementAbove = true,
                        avatarLeadingPadding = dimens.defaultPadding * 2
                    )

                    // Botones de acción — superpuestos en la parte superior, encima de las cartas
                    ActionButtons(
                        actions = gameState.availableActions,
                        gamePhase = gameState.gamePhase,
                        onActionClick = { action, playerId -> gameViewModel.onAction(action, playerId) },
                        selectedCardCount = gameState.selectedCardsForDiscard.size,
                        isEnabled = isMyTurn,
                        currentPlayerId = gameViewModel.humanPlayerId,
                        isRaise = gameState.currentBet != null,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(start = dimens.defaultPadding, top = dimens.actionButtonsPadding, end = dimens.defaultPadding)
                            .zIndex(2f),
                        dimens = dimens
                    )

                    // Botones Seña y Pausa — esquina inferior izquierda
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(dimens.smallPadding)
                            .zIndex(3f),
                        horizontalArrangement = Arrangement.spacedBy(dimens.smallPadding)
                    ) {
                        // Seña: oculta durante mus corrido (#17: prohibidas las
                        // señas hasta el primer corte que determina la mano).
                        // En su lugar, un indicador del modo que explica por qué
                        // no hay botón de seña.
                        if (!gameState.musCorrido) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp * scaleFactor)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .clickable { gameViewModel.onAction(GameAction.ShowGesture, player.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Seña", color = Color.White, fontSize = dimens.fontSizeLarge)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .height(58.dp * scaleFactor)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = dimens.defaultPadding),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Mus corrido\nsin señas",
                                    color = Color.White,
                                    fontSize = dimens.fontSizeSmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(58.dp * scaleFactor)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .clickable { gameViewModel.onAction(GameAction.TogglePauseMenu, player.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_pause),
                                contentDescription = "Pausa",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp * scaleFactor)
                            )
                        }
                    }
                }
            }


            // ── CAPA 3: overlays de pantalla completa ──
            GameOverlays(
                gameState = gameState,
                players = players,
                dimens = dimens,
                screenHeight = screenHeight,
                navController = navController,
                gameViewModel = gameViewModel
            )
        }
    }
}

/**
 * Capa de overlays de pantalla completa, emitida como último hijo del Box
 * raíz (mismo z-order que antes): pausa, paneles de debug, selector de
 * envite, fin de partida, fin de ronda y notificación de evento. Extraída de
 * la raíz de GameScreen sin cambiar comportamiento ni layout.
 */
@Composable
private fun GameOverlays(
    gameState: GameState,
    players: List<Player>,
    dimens: ResponsiveDimens,
    screenHeight: Dp,
    navController: NavController,
    gameViewModel: GameViewModel
) {
    if (gameState.isPaused) {
        PauseMenuOverlay(
            navController = navController,
            onAction = gameViewModel::onAction,
            humanPlayerId = gameViewModel.humanPlayerId,
            dimensions = dimens,
            gameViewModel = gameViewModel
        )
    }

    // Panel flotante de logs de IA — solo se renderiza en builds debug.
    DebugFeatures.AiDebugPanelOverlay(gameViewModel)

    // Selector de escenarios de prueba — solo se renderiza en builds debug.
    DebugFeatures.ScenarioSelectorOverlay(gameViewModel)

    if (gameState.isSelectingBet) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = screenHeight * 0.30f),
            contentAlignment = Alignment.BottomCenter
        ) {
            BetSelector(
                onBet = { amount ->
                    gameViewModel.onAction(GameAction.Envido(amount), gameViewModel.humanPlayerId)
                },
                onCancel = {
                    gameViewModel.onAction(GameAction.Paso, gameViewModel.humanPlayerId)
                },
                isRaise = gameState.currentBet != null
            )
        }
    }

    if (gameState.gamePhase == GamePhase.GAME_OVER && gameState.winningTeam != null) {
        GameOverOverlay(
            winnerTeam = gameState.winningTeam,
            ordagoInfo = gameState.ordagoInfo,
            players = players,
            bottomPadding = screenHeight * 0.28f,
            onNewGameClick = {
                gameViewModel.onAction(GameAction.NewGame, gameViewModel.humanPlayerId)
            }
        )
    }

    if (gameState.gamePhase == GamePhase.ROUND_OVER && gameState.scoreBreakdown != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = screenHeight * 0.55f),
            contentAlignment = Alignment.Center
        ) {
            RoundEndOverlay(
                breakdown = gameState.scoreBreakdown,
                onContinueClick = {
                    gameViewModel.onAction(GameAction.Continue, gameViewModel.humanPlayerId)
                },
                dimens = dimens
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GameEventNotification(event = gameState.event)
    }
}



//A Composable to display a list of action buttons
@Composable
private fun GameActionButton(
    action: GameAction,
    onClick: () -> Unit,
    isEnabled: Boolean,
    dimens: ResponsiveDimens,
    labelOverride: String? = null
) {
    val buttonColors = when (action.actionType) {
        ActionType.PASS -> ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ActionType.BET -> ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEB3B))
        ActionType.CONFIRM, ActionType.DISCARD -> ButtonDefaults.buttonColors(
            containerColor = Color(
                0xFF4CAF50
            )
        )

        ActionType.DENY -> ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
        ActionType.ULTIMATE -> ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
    }

    val secondaryColor = Color.Black

    Button(
        onClick = onClick,
        colors = buttonColors,
        enabled = isEnabled,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        modifier = Modifier.height(48.dp * dimens.scaleFactor)
    ) {
        if (action.iconResId != null) {

            if (action.actionType == ActionType.BET && isEnabled) {
                // Get the TextUnit value (e.g., 15.sp * 1.5 = 22.5.sp)
                val iconSizeSp =
                    dimens.fontSizeLarge

                // Convert the sp value to Dp.
                // While there isn't a direct .toDp() from sp, you can use the .value
                // and treat it as a dp value if that's your design intent.
                // Or, more accurately, if you want the size in Dp that sp would occupy,
                // you'd use LocalDensity, but for icons, directly using the value as dp is common.
                val iconSizeDp =
                    iconSizeSp.value.dp // Takes the float value (e.g., 22.5f) and converts to Dp

                Icon(
                    painter = painterResource(id = action.iconResId),
                    contentDescription = null,
                    modifier = Modifier.size(iconSizeDp),
                    tint = secondaryColor
                )
            }else{
                    Icon(
                painter = painterResource(id = action.iconResId),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
                )
            }

            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }

        val label = labelOverride ?: action.displayText
        if (action.actionType == ActionType.BET && isEnabled) {
            Text(text = label, color = secondaryColor, fontSize = dimens.fontSizeMedium)
        } else if (action.actionType == ActionType.DISCARD) {
            Text(text = "Descartar", fontSize = dimens.fontSizeMedium)
        } else {
            Text(text = label, fontSize = dimens.fontSizeMedium)
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
    isRaise: Boolean,
    modifier: Modifier = Modifier,
    dimens: ResponsiveDimens
) {
    val availableActionsMap = actions.associateBy {
        if (it is GameAction.Envido) GameAction.Envido::class else it::class
    }

    // --- Layout principal que cambia según la fase del juego ---
    Box(
        modifier = modifier.padding(top = dimens.buttonVPadding, bottom = dimens.buttonVPadding),
        contentAlignment = Alignment.Center
    ) {
        when (gamePhase) {
            GamePhase.MUS -> {
                // FASE MUS: Solo se muestran los botones de Mus
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor)
                ) {
                    GameActionButton(
                        action = GameAction.Mus,
                        onClick = { onActionClick(GameAction.Mus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Mus::class),
                        dimens = dimens
                    )
                    GameActionButton(
                        action = GameAction.NoMus,
                        onClick = { onActionClick(GameAction.NoMus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.NoMus::class),
                        dimens = dimens
                    )
                }
            }

            GamePhase.DISCARD -> {
                // FASE DESCARTE: Solo se muestra el botón de descartar
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    val discardAction = GameAction.ConfirmDiscard
                    GameActionButton(
                        action = discardAction,
                        onClick = { onActionClick(discardAction, currentPlayerId) },
                        isEnabled = isEnabled && selectedCardCount > 0,
                        dimens = dimens
                    )
                }
            }

            else -> {
                // FASES DE APUESTA (GRANDE, CHICA, PARES, JUEGO): Layout fijo de dos columnas

                // Columna Izquierda: Respuestas
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Bottom

                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {

                        val quieroAction = GameAction.Quiero
                        GameActionButton(
                            action = quieroAction,
                            onClick = { onActionClick(quieroAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(quieroAction::class),
                            dimens = dimens
                        )
                        val noQuieroAction = GameAction.NoQuiero
                        GameActionButton(
                            action = noQuieroAction,
                            onClick = { onActionClick(noQuieroAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(noQuieroAction::class),
                            dimens = dimens
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pasoAction = GameAction.Paso
                        GameActionButton(
                            action = pasoAction,
                            onClick = { onActionClick(pasoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(pasoAction::class),
                            dimens = dimens
                        )
                        val envidoAction = GameAction.ToggleBetSelector
                        GameActionButton(
                            action = envidoAction,
                            onClick = {
                                onActionClick(
                                    envidoAction,
                                    currentPlayerId
                                )
                            }, // Ahora envía la acción de mostrar el selector
                            isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Envido::class),
                            dimens = dimens,
                            // Si ya hay envite en juego, este botón sube, no abre (#18).
                            labelOverride = if (isRaise) "Subir" else null
                        )
                        val ordagoAction = GameAction.Órdago
                        GameActionButton(
                            action = ordagoAction,
                            onClick = { onActionClick(ordagoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(ordagoAction::class),
                            dimens = dimens
                        )
                    }
                }
            }
        }
    }
}
//Composable for the back of a card without the Surface wrapper
@Composable
fun CardBack(modifier: Modifier = Modifier, dimens: ResponsiveDimens) { // <-- ADD THIS PARAMETER
    Image(
        painter = painterResource(id = R.drawable.card_back),
        contentDescription = "Card back",
        // Apply the passed-in modifier here, then add our specific ones
        modifier = modifier
            .width(dimens.cardBackWidth)
            .aspectRatio(dimens.cardAspectRatio)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp))
            .bottomBorder(1.dp, Color.Black.copy(alpha = 0.5f))
    )
}

//Composable for a single card without the Surface wrapper
@Composable
private fun GameCard(
    card: CardData,
    isSelected: Boolean,
    onClick: () -> Unit,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    modifier: Modifier = Modifier,
    dimens: ResponsiveDimens
) {

    Image(
        painter = cardToPainter(card = card),
        contentDescription = "${card.rank} of ${card.suit}",
        modifier = modifier
            .width(dimens.cardWidth)
            .aspectRatio(dimens.cardAspectRatio)
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(4.dp), clip = false)
            .graphicsLayer {
                clip = if (isSelected) false else true
                translationY = if (isSelected) -80f else 0f
            }
            .clickable(
                enabled = (gamePhase == GamePhase.DISCARD && isMyTurn), // Only enabled in discard phase
                onClick = { onClick() }
            )
    )
}

/**
 * #15: ¿el jugador participa en el lance que se está jugando ahora? Solo
 * PARES/JUEGO tienen subconjunto (no todos tienen pares/juego); el resto de
 * fases `playersInLance` = todos o vacío -> nadie se atenúa. En *_CHECK el
 * subconjunto aún no está fijado, así que no atenuamos hasta tenerlo.
 */
private fun isPlayerInActiveLance(gameState: GameState, playerId: String): Boolean =
    when (gameState.gamePhase) {
        GamePhase.PARES, GamePhase.JUEGO ->
            gameState.playersInLance.isEmpty() || playerId in gameState.playersInLance
        else -> true
    }

/** Icono pequeño superpuesto en una esquina del avatar (mano / corta-mus). */
@Composable
private fun BoxScope.AvatarCornerIcon(
    iconResId: Int,
    description: String,
    alignment: Alignment,
    dimens: ResponsiveDimens
) {
    Icon(
        painter = painterResource(id = iconResId),
        contentDescription = description,
        tint = Color.White,
        modifier = Modifier
            .align(alignment)
            .size(dimens.avatarSize / 3)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .padding(4.dp)
    )
}

@Composable
private fun PlayerAvatar(
    player: Player,
    isCurrentTurn: Boolean, // This will control the glow
    modifier: Modifier = Modifier,
    isMano: Boolean,
    hasCutMus: Boolean,
    activeGestureResId: Int?,
    discardCount: Int? = null,
    isInLance: Boolean = true,
    dimens: ResponsiveDimens
) {
    val borderColor = if (isCurrentTurn) Color.Yellow else Color.Transparent

    Box(
        modifier = modifier.size(dimens.avatarSize),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = player.avatarResId),
            contentDescription = "Avatar of ${player.name}",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (isInLance) 1f else DIMMED_AVATAR_ALPHA }
                .clip(CircleShape)
                .border(4.dp, borderColor, CircleShape)
        )
        AnimatedVisibility(
            visible = activeGestureResId != null,
            enter = EnterTransition.None,
            exit = fadeOut() + scaleOut()
        ) {
            if (activeGestureResId != null) {
                Icon(
                    painter = painterResource(id = activeGestureResId),
                    contentDescription = "Seña",
                    tint = Color.LightGray,
                    modifier = Modifier
                        .fillMaxSize(0.9f)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.1f), CircleShape)
                )
            }
        }

        // Iconos de esquina: mano (abajo-dcha) y corta-mus (abajo-izda).
        if (isMano) {
            AvatarCornerIcon(R.drawable.ic_mano, "Indicador de Mano", Alignment.BottomEnd, dimens)
        }
        if (hasCutMus) {
            AvatarCornerIcon(R.drawable.ic_cut, "Indicador de Corta Mus", Alignment.BottomStart, dimens)
        }

        // Badge de descarte: cuántas cartas cambió este jugador en el ciclo
        // de Mus/descarte ACTUAL. Acotado a la fase de Mus/descarte
        // (discardCounts se vacía al entrar en GRANDE y al iniciar cada
        // ciclo de descarte). Esquina libre (mano = abajo-dcha, corta-mus =
        // abajo-izda, seña = centro).
        if (discardCount != null && discardCount > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_cycle),
                    contentDescription = "Cartas descartadas",
                    tint = Color.White,
                    modifier = Modifier.size(dimens.avatarSize / 5)
                )
                Text(
                    text = "$discardCount",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = dimens.fontSizeMedium,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

@Composable
fun Scoreboard(score: Map<String, Int>, modifier: Modifier = Modifier, dimens: ResponsiveDimens? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "NOSOTROS: ${score["teamA"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimens?.fontSizeMedium ?: 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ELLOS: ${score["teamB"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimens?.fontSizeMedium ?: 14.sp
            )
        }
    }
}

@Composable
fun ActionAnnouncement(
    player: Player,
    gameState: GameState,
    dimens: ResponsiveDimens
) {
    // El game state declara QUÉ anuncio quiere mostrar; el composable decide
    // CUÁNDO y CÓMO. Fuente ÚNICA: currentLanceActions[player] (la lógica la
    // muta solo de forma síncrona). Un único valor monótono por jugador ⇒ sin
    // carrera viejo↔nuevo (#27). El mínimo visible y el desvanecido al quedar
    // null los gestiona el LaunchedEffect de abajo, en local.
    val targetAction: LastActionInfo? = gameState.currentLanceActions[player.id]

    var displayedAction by remember { mutableStateOf<LastActionInfo?>(null) }
    var shownAt by remember { mutableStateOf(0L) }

    LaunchedEffect(targetAction) {
        val now = System.currentTimeMillis()
        when {
            targetAction != null && targetAction != displayedAction -> {
                if (displayedAction != null) {
                    val remaining = ANNOUNCEMENT_MIN_BEFORE_REPLACE_MS - (now - shownAt)
                    if (remaining > 0) delay(remaining)
                }
                displayedAction = targetAction
                shownAt = System.currentTimeMillis()
            }
            targetAction == null && displayedAction != null -> {
                val remaining = ANNOUNCEMENT_MIN_VISIBLE_MS - (now - shownAt)
                if (remaining > 0) delay(remaining)
                displayedAction = null
            }
        }
    }

    AnimatedVisibility(
        visible = displayedAction != null,
        enter = fadeIn(tween(ANNOUNCEMENT_ENTER_MS)) +
                slideInVertically(tween(ANNOUNCEMENT_ENTER_MS)),
        exit = fadeOut(tween(ANNOUNCEMENT_EXIT_MS)) +
                slideOutVertically(tween(ANNOUNCEMENT_EXIT_MS))
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier.padding(4.dp * dimens.scaleFactor)
        ) {
            AnimatedContent(
                targetState = displayedAction,
                transitionSpec = {
                    fadeIn(tween(ANNOUNCEMENT_TEXT_FADE_MS)) togetherWith
                            fadeOut(tween(ANNOUNCEMENT_TEXT_FADE_MS))
                },
                label = "ActionAnnouncementText"
            ) { action ->
                val act = action?.action
                val text = when {
                    act is GameAction.ConfirmDiscard ->
                        "Dame ${gameState.discardCounts[player.id]}"
                    // Subida de envite (amount != null ⇒ había envite previo):
                    // mostrar el incremento como "N más", no "Envido N" (#18).
                    act is GameAction.Envido && action.amount != null ->
                        "${act.amount} más"
                    else -> act?.displayText ?: ""
                }
                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = dimens.fontSizeLarge,
                    modifier = Modifier.padding(
                        horizontal = 12.dp * dimens.scaleFactor,
                        vertical = 5.dp * dimens.scaleFactor
                    )
                )
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun PlayerHandArc(
    cards: List<CardData>,
    selectedCards: Set<CardData>,
    gamePhase: GamePhase,
    isMyTurn: Boolean,
    onCardClick: (CardData) -> Unit,
    dimens: ResponsiveDimens
) {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = dimens.defaultPadding * 2),
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val isSelected = card in selectedCards

            val centerOffset = index - (cardCount - 1) / 2f
            val rotation = centerOffset * 5f
            val translationY = abs(centerOffset) * -1f
            val translationX = centerOffset * 230f * dimens.scaleFactor

            GameCard(
                card = card,
                isSelected = isSelected,
                gamePhase = gamePhase,
                isMyTurn = isMyTurn,
                onClick = { onCardClick(card) },

                modifier = Modifier
                    .zIndex(if (isSelected) 1f else 0f)
                    .graphicsLayer {

                        this.rotationZ = if (isSelected) 0f else rotation
                        this.translationY = translationY
                        this.translationX = translationX
                        this.clip = false
                    },
                dimens = dimens
            )
        }
    }
}

@Composable
private fun PartnerHand(
    modifier: Modifier = Modifier,
    cards: List<CardData>,
    isDebugMode: Boolean,
    revealHand: Boolean,
    dimens: ResponsiveDimens
) {
    val showFaceUp = (DebugFeatures.IS_ENABLED && isDebugMode) || revealHand
    Row(
        modifier = modifier,
        horizontalArrangement = if (showFaceUp)
            Arrangement.spacedBy(-(dimens.cardWidth * 0.30f))
        else
            Arrangement.spacedBy(0.dp)
    ) {
        cards.forEach { card ->
            if (showFaceUp) {

                GameCard(
                    card = card,
                    isSelected = false,
                    gamePhase = GamePhase.PRE_GAME,
                    isMyTurn = false,
                    onClick = {},
                    dimens = dimens
                )
            } else {

                CardBack(
                    dimens = dimens
                )
            }
        }
    }
}

@Composable
fun SideOpponentHandStacked(modifier: Modifier, cards: List<CardData>, isDebugMode: Boolean, revealHand: Boolean, rotate: Boolean = false, dimens: ResponsiveDimens) {
    Box (
        modifier = Modifier.offset(y = -dimens.smallPadding)
    ) {
        repeat(cards.size) { index ->
            Box(
                modifier = Modifier.offset(y = (index * dimens.cardWidth * 0.5f))
                    .graphicsLayer {
                        if (rotate) rotationZ = 90f else rotationZ = 270f
                    }
            ) {
                if ((DebugFeatures.IS_ENABLED && isDebugMode) || revealHand) {
                    GameCard(
                        card = cards[index],
                        isSelected = false,
                        gamePhase = GamePhase.PRE_GAME,
                        isMyTurn = false,
                        onClick = {},
                        dimens = dimens
                    )
                } else {
                    CardBack(dimens = dimens)
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
    handContent: @Composable (() -> Unit),
    hasCutMus: Boolean,
    activeGesture: ActiveGestureInfo?,
    dimens: ResponsiveDimens,
    gameState: GameState
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                // Tamaño 0 en ambos ejes: overlay puro, no desplaza a los hermanos
                // por mucho que crezca el texto. Se centra sobre el avatar.
                layout(0, 0) {
                    placeable.placeRelative(-placeable.width / 2, -placeable.height)
                }
            }
        ) {
            ActionAnnouncement(player = player, gameState = gameState, dimens = dimens)
        }
        PlayerAvatar(player = player,
            isCurrentTurn = isCurrentTurn,
            isMano = isMano, hasCutMus = hasCutMus,
            dimens = dimens,
            activeGestureResId = if (activeGesture?.playerId == player.id) activeGesture.gestureResId else null,
            discardCount = gameState.discardCounts[player.id],
            isInLance = isPlayerInActiveLance(gameState, player.id))
        handContent()
    }
}

@Composable
fun HorizontalPlayerArea(
    player: Player,
    isCurrentTurn: Boolean,
    isMano: Boolean,
    handContent: @Composable (() -> Unit),
    hasCutMus: Boolean,
    activeGesture: ActiveGestureInfo?,
    dimens: ResponsiveDimens,
    modifier: Modifier = Modifier,
    gameState: GameState,
    announcementAbove: Boolean = false,
    avatarLeadingPadding: Dp = 0.dp
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(start = avatarLeadingPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (announcementAbove) {
                Box(
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        // Tamaño 0: overlay centrado sobre el avatar, no reordena la fila.
                        layout(0, 0) {
                            placeable.placeRelative(-placeable.width / 2, -placeable.height)
                        }
                    }
                ) {
                    ActionAnnouncement(player = player, gameState = gameState, dimens = dimens)
                }
            }
            PlayerAvatar(
                player = player,
                isCurrentTurn = isCurrentTurn,
                isMano = isMano,
                hasCutMus = hasCutMus,
                dimens = dimens,
                activeGestureResId =
                    if (activeGesture?.playerId == player.id) activeGesture.gestureResId else null,
                discardCount = gameState.discardCounts[player.id],
                isInLance = isPlayerInActiveLance(gameState, player.id)
            )
            if (!announcementAbove) {
                Box(
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        // Tamaño 0: overlay centrado bajo el avatar, no reordena la fila.
                        layout(0, 0) {
                            placeable.placeRelative(-placeable.width / 2, 0)
                        }
                    }
                ) {
                    ActionAnnouncement(player = player, gameState = gameState, dimens = dimens)
                }
            }
        }
        Box(contentAlignment = Alignment.Center) {
            handContent()
        }
    }
}

@Composable
fun GameOverOverlay(
    winnerTeam: String,
    ordagoInfo: OrdagoInfo?,
    players: List<Player>,
    bottomPadding: Dp = 240.dp,
    onNewGameClick: () -> Unit
) {
    // Lógica para mostrar el texto del órdago
    val titleText: String
    val subtitleText: String
    if (ordagoInfo != null) {
        val winnerPlayer = players.find { it.id == ordagoInfo.winnerId }
        titleText = "¡VICTORIA POR ÓRDAGO!"
        subtitleText =
            "Gana el equipo de ${winnerPlayer?.name ?: ""} en ${ordagoInfo.lance.name}"
    } else {
        titleText = if (winnerTeam == "teamA") "¡HAS GANADO!" else "HAS PERDIDO"
        subtitleText = "La partida ha terminado"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = bottomPadding)
        ) {
            Text(
                text = titleText,
                color = if (ordagoInfo != null) Color.Red else Color.Yellow,
                fontSize = 22.sp
            )
            Text(
                text = subtitleText,
                color = Color.White,
                fontSize = 18.sp
            )
            Button(onClick = onNewGameClick) {
                Text(text = "Jugar de Nuevo", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun GameEventNotification(event: GameEvent?) {
    // This state will be true only for a moment when a new event arrives
    var visible by remember(event) { mutableStateOf(event != null) }

    // After 3 seconds, we hide the notification
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0)), // Morado
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

// En: GameScreen.kt
// Orden canónico de los lances para mostrar el desglose (#24): el `reason`
// es texto libre ("GRANDE (Apuesta)", "Duples (nombre)", "Juego 31...", "Punto").
private fun lanceOrderRank(reason: String): Int {
    val r = reason.uppercase()
    return when {
        r.contains("GRANDE") -> 0
        r.contains("CHICA") -> 1
        r.contains("PARES") || r.contains("MEDIAS") || r.contains("DUPLES") -> 2
        r.contains("JUEGO") || r.contains("PUNTO") -> 3
        else -> 4
    }
}

// Columna de puntuación de un equipo, ordenada por lance. Reutilizada por el
// fin de ronda (#24) y el resumen de fin de partida (#26); antes eran dos
// bloques casi idénticos inline.
@Composable
private fun TeamScoreColumn(
    title: String,
    details: List<ScoreDetail>,
    dimens: ResponsiveDimens
) {
    val ordered = details.sortedWith(compareBy { lanceOrderRank(it.reason) })
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            fontSize = dimens.fontSizeMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        ordered.forEach { detail ->
            Row(
                modifier = Modifier.width(150.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(detail.reason, color = Color.White, fontSize = dimens.fontSizeSmall)
                Text(
                    "+${detail.points}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.height(1.dp).width(150.dp).background(Color.Gray))
        Row(
            modifier = Modifier.width(150.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Total Ronda", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "+${details.sumOf { it.points }}",
                color = Color.Yellow,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RoundEndOverlay(
    breakdown: ScoreBreakdown,
    onContinueClick: () -> Unit,
    dimens: ResponsiveDimens
) {
    // La Card ahora es el elemento principal, sin un Box que ocupe toda la pantalla
    Card(
        // Le damos un borde y una elevación para que "flote" sobre el fondo
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, Color.Yellow.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E).copy(alpha = 0.9f)) // Hacemos el fondo casi opaco para legibilidad
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "FIN DE LA RONDA",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Yellow
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                TeamScoreColumn("NOSOTROS", breakdown.teamAScoreDetails, dimens)
                TeamScoreColumn("ELLOS", breakdown.teamBScoreDetails, dimens)
            }

            Button(onClick = onContinueClick) {
                Text("Continuar", fontSize = 12.sp)
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
    modifier: Modifier = Modifier,
    dimens: ResponsiveDimens
) {
    val lances = listOf(GamePhase.GRANDE, GamePhase.CHICA, GamePhase.PARES, GamePhase.JUEGO)

    // En las fases de declaración (¿tengo pares/juego?) el nombre del lance
    // debe iluminarse igual, para saber de qué lance se está decidiendo (#22).
    val highlightedPhase = when (currentPhase) {
        GamePhase.PARES_CHECK -> GamePhase.PARES
        GamePhase.JUEGO_CHECK -> GamePhase.JUEGO
        else -> currentPhase
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = dimens.smallPadding),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            lances.forEach { lance ->
                val isCurrent = (highlightedPhase == lance)
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

                val lanceName = if (lance == GamePhase.JUEGO && isPuntoPhase) {
                    "PUNTO"
                } else {
                    lance.name
                }

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
                    Text(
                        text = lanceName,
                        modifier = Modifier.weight(1f),
                        color = textColor,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontSize = dimens.fontSizeMedium
                    )
                    if (resultText.isNotEmpty()) {
                        Text(
                            text = resultText,
                            color = Color.Gray,
                            fontSize = dimens.fontSizeMedium,
                            softWrap = false
                        )
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
    isRaise: Boolean = false
) {
    val minBet = if (isRaise) 1 else 2
    var betAmount by remember { mutableStateOf(minBet) }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                if (isRaise) "¿Cuánto quieres subir?" else "¿Cuántos quieres envidar?",
                color = Color.White,
                fontSize = 18.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = { if (betAmount > minBet) betAmount-- }) {
                    Text("-", fontSize = 24.sp)
                }
                Text(
                    betAmount.toString(),
                    color = Color.Yellow,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = { if (betAmount < MAX_BET) betAmount++ }) {
                    Text("+", fontSize = 24.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Cancelar")
                }
                Button(onClick = { onBet(betAmount) }) {
                    Text("¡Envidar!")
                }
            }
        }
    }
}

@Composable
fun PauseMenuOverlay(
    navController: NavController,
    onAction: (GameAction, String) -> Unit,
    humanPlayerId: String,
    dimensions: ResponsiveDimens,
    gameViewModel: GameViewModel
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = false, onClick = {}), // Bloquea los clics al juego
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(dimensions.defaultPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimensions.defaultPadding)
            ) {
                Text(
                    text = "PAUSA",
                    fontSize = dimensions.fontSizeLarge.times(1.5f),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                // Botón para reanudar el juego
                Button(onClick = { onAction(GameAction.TogglePauseMenu, humanPlayerId) }) {
                    Text("Reanudar", fontSize = dimensions.fontSizeMedium)
                }
                // Botón para empezar una nueva partida
                Button(
                    onClick = { onAction(GameAction.NewGame, humanPlayerId) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("Nueva Partida", fontSize = dimensions.fontSizeMedium)
                }

                Button(
                    onClick = { navController.popBackStack() }, // Vuelve a la pantalla anterior (el menú)
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("Volver al Menú", fontSize = dimensions.fontSizeMedium)
                }

                // Botón de debug — solo se renderiza en builds debug.
                DebugFeatures.DebugToggleButton(gameViewModel)
            }
        }
    }
}

data class ResponsiveDimens(
    val cardWidth: Dp,
    val cardBackWidth: Dp,
    val cardAspectRatio: Float,
    val avatarSize: Dp,
    val handArcTranslationX: Float,
    val handArcTranslationY: Float,
    val handArcRotation: Float,
    val largePadding: Dp,
    val defaultPadding: Dp,
    val smallPadding: Dp,
    val fontSizeLarge: TextUnit,
    val fontSizeMedium: TextUnit,
    val fontSizeSmall: TextUnit,
    val buttonVPadding: Dp,
    val buttonHPadding: Dp,
    val scaleFactor: Float,
    val actionButtonsPadding: Dp
)
