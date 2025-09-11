package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.ActionType
import com.doselfurioso.musvisto.model.ActiveGestureInfo
import com.doselfurioso.musvisto.model.BetInfo
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameEvent
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LanceResult
import com.doselfurioso.musvisto.model.OrdagoInfo
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.ScoreBreakdown
import kotlinx.coroutines.delay
import kotlin.math.abs
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


@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun GameScreen(
    gameViewModel: GameViewModel,
    navController: NavController
){
    val gameState by gameViewModel.gameState.collectAsState()
    val isDebugMode by gameViewModel.isDebugMode.collectAsState()
    val players = gameState.players
    val gameLogic: MusGameLogic = gameViewModel.gameLogic


    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF006A4E))
    ) {

        val screenWidth = maxWidth

        // --- INICIO DE LA NUEVA LÓGICA DE ESCALADO ---

        // 1. Definimos los límites de la pantalla con los que vamos a trabajar.
        //    - `minWidth`: El ancho de una pantalla muy pequeña (ej. 320dp).
        //    - `maxWidth`: El ancho de una pantalla grande/tablet (ej. 800dp).
        val minWidth = 320.dp
        val maxWidth = 800.dp

        // 2. Definimos los factores de escala que queremos en esos límites.
        //    - En la pantalla más pequeña, queremos que todo se reduzca un 15% (escala de 0.85).
        //    - En la pantalla más grande, queremos que todo aumente un 20% (escala de 1.2).
        //    ¡ESTOS SON LOS VALORES QUE PUEDES AJUSTAR PARA EL "LOOK & FEEL"!
        val minScale = 0.50f
        val maxScale = 2f

        // 3. Calculamos el progreso actual de la pantalla dentro de nuestro rango.
        //    `coerceIn` asegura que el valor no se salga de los límites 0.0 a 1.0.
        val progress = ((screenWidth - minWidth) / (maxWidth - minWidth)).coerceIn(0f, 1f)

        // 4. Interpolamos para obtener nuestro factor de escala final.
        //    Esta fórmula calcula el punto exacto entre `minScale` y `maxScale`
        //    que corresponde al `progress` actual.
        val scaleFactor = minScale + progress * (maxScale - minScale)

        // --- FIN DE LA NUEVA LÓGICA DE ESCALADO ---



        val dimens = remember(scaleFactor) {
            ResponsiveDimens(
                // Ahora usamos este nuevo `scaleFactor` más equilibrado.
                // Podemos ser un poco más generosos con los límites de `coerceIn`.
                cardWidth = (300.dp * scaleFactor * scaleFactor).coerceIn(70.dp, 120.dp),
                cardAspectRatio = 0.7f,
                avatarSize = (85.dp * scaleFactor).coerceIn(40.dp, 100.dp),
                handArcTranslationX = (150f * scaleFactor),
                handArcTranslationY = (15f * scaleFactor),
                handArcRotation = 5f,
                defaultPadding = (32.dp * scaleFactor),
                smallPadding = (8.dp * scaleFactor),
                fontSizeLarge = (20.sp * scaleFactor),
                fontSizeMedium = (15.sp * scaleFactor),
                fontSizeSmall = (12.sp * scaleFactor),
                // Los offsets verticales pueden seguir dependiendo del alto para evitar solapamientos
                sidePlayerVerticalOffset = (maxHeight * 0.3f).coerceAtMost(280.dp),
                actionButtonsVerticalOffset = (maxHeight * 0.22f).coerceIn(130.dp, 220.dp),
                actionbuttonsSize = (10.dp * scaleFactor).coerceIn(0.dp, 180.dp),
                buttonVPadding = (10.dp * scaleFactor),
                buttonHPadding = (10.dp * scaleFactor),
                scaleFactor = scaleFactor.dp // 0'6 small - 0'9 big screen

            )
        }

    Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
        if (gameState.isPaused) {
            PauseMenuOverlay(
                navController = navController,
                onAction = gameViewModel::onAction,
                humanPlayerId = gameViewModel.humanPlayerId,
                dimensions = dimens
            )
        }
    }



        if (gameState.players.size == 4) {
            val player = players[0]
            val rivalLeft = players[1]
            val partner = players[2]
            val rivalRight = players[3]

            val isMyTurn = gameState.currentTurnPlayerId == gameViewModel.humanPlayerId
            val currentPlayer = players.find { it.id == gameState.currentTurnPlayerId }
            var actionsForUi = gameState.availableActions
            if (currentPlayer?.id == gameViewModel.humanPlayerId) {
                val playerHand = currentPlayer.hand
                actionsForUi = when (gameState.gamePhase) {
                    GamePhase.PARES -> {
                        if (gameLogic.getHandPares(playerHand).strength > 0) gameState.availableActions
                        else listOf(GameAction.Paso)
                    }

                    GamePhase.JUEGO -> {
                        if (gameState.isPuntoPhase || gameLogic.getHandJuegoValue(playerHand) >= 31) gameState.availableActions
                        else listOf(GameAction.Paso)
                    }

                    else -> gameState.availableActions
                }
            }

            IconButton(
                onClick = { gameViewModel.onToggleDebugMode() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 16.dp)
                    .zIndex(3f)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_debug),
                    contentDescription = "Toggle Debug Mode",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }

            // --- ANUNCIOS DE ACCIÓN ---
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp * scaleFactor, top = 250.dp * scaleFactor)
            ) {
                ActionAnnouncement(rivalLeft, gameState, dimens)
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp * scaleFactor, top = 250.dp * scaleFactor)
            ) {
                ActionAnnouncement(rivalRight, gameState, dimens)
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 40.dp * scaleFactor, top = 170.dp * scaleFactor)
            ) {
                ActionAnnouncement(partner, gameState, dimens)
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 32.dp * scaleFactor, bottom = 240.dp * scaleFactor)
            ) {
                ActionAnnouncement(player, gameState, dimens)
            }
            // ÁREA DEL COMPAÑERO (ARRIBA) - Vertical
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp * dimens.scaleFactor.value)
            ) {
                HorizontalPlayerArea(
                    player = partner,
                    isCurrentTurn = gameState.currentTurnPlayerId == partner.id,
                    isMano = gameState.manoPlayerId == partner.id,
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
                    dimens = dimens
                )
            }

            // ÁREA DEL RIVAL IZQUIERDO - Horizontal
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = dimens.defaultPadding * scaleFactor, bottom = dimens.sidePlayerVerticalOffset)
            ) {
                VerticalPlayerArea(
                    player = rivalLeft,
                    isCurrentTurn = gameState.currentTurnPlayerId == rivalLeft.id,
                    isMano = gameState.manoPlayerId == rivalLeft.id,
                    hasCutMus = gameState.noMusPlayer == rivalLeft.id,
                    activeGesture = gameState.activeGesture,
                    handContent = {
                        SideOpponentHandStacked(
                            modifier = Modifier
                                .padding(16.dp)
                                .graphicsLayer { rotationX = 90f },
                            cards = rivalLeft.hand.sortedByDescending { it.rank.value },
                            isDebugMode = isDebugMode,
                            revealHand = gameState.revealAllHands,
                            rotate = true,
                            dimens = dimens
                        )
                    },
                    dimens = dimens
                )
            }

            // ÁREA DEL RIVAL DERECHO - Horizontal
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = dimens.defaultPadding * scaleFactor, bottom = dimens.sidePlayerVerticalOffset)
            ) {
                VerticalPlayerArea(
                    player = rivalRight,
                    isCurrentTurn = gameState.currentTurnPlayerId == rivalRight.id,
                    isMano = gameState.manoPlayerId == rivalRight.id,
                    hasCutMus = gameState.noMusPlayer == rivalRight.id,
                    activeGesture = gameState.activeGesture,
                    handContent = {
                        SideOpponentHandStacked(
                            modifier = Modifier
                                .graphicsLayer { rotationZ = 180f },
                            cards = rivalRight.hand.sortedBy { it.rank.value },
                            isDebugMode = isDebugMode,
                            revealHand = gameState.revealAllHands,
                            rotate = false,
                            dimens = dimens
                        )
                    },
                    dimens = dimens
                )
            }

            // ÁREA DEL JUGADOR PRINCIPAL (ABAJO) - Vertical
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp * dimens.scaleFactor.value, start = 32.dp * dimens.scaleFactor.value)
            ) {
                IconButton(
                    onClick = { gameViewModel.onAction(GameAction.ShowGesture, player.id) },
                    modifier = Modifier
                        .padding(bottom = 40.dp * scaleFactor, top = 100.dp * scaleFactor)
                        .align(Alignment.BottomStart) // Alineado arriba y al centro del área
                        .size(50.dp * dimens.scaleFactor.value)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))

                ) {
                    Text(
                        text = "Seña",
                        color = Color.White,
                        fontSize = dimens.fontSizeLarge
                    )
                }

                IconButton(
                    onClick = { gameViewModel.onAction(GameAction.TogglePauseMenu, player.id) },
                    modifier = Modifier
                        .padding(bottom = 40.dp * scaleFactor, top = 100.dp * scaleFactor, start = 70.dp * scaleFactor)
                        .align(Alignment.BottomStart) // Alineado arriba y al centro del área
                        .size(50.dp * dimens.scaleFactor.value)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pause), // Cambia este icono por uno de pausa si lo tienes
                        contentDescription = "Pausa",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp * scaleFactor)
                    )
                }



                HorizontalPlayerArea(
                    player = player,
                    isCurrentTurn = isMyTurn,
                    isMano = gameState.manoPlayerId == player.id,
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
                    dimens = dimens
                )
            }

            // INFO CENTRAL Y BOTONES
            // Dentro del Column central en GameScreen
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 200.dp), // Quitamos el padding lateral innecesario
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Scoreboard(score = gameState.score)
                LanceTracker(
                    currentPhase = gameState.gamePhase,
                    history = gameState.roundHistory,
                    isPuntoPhase = gameState.isPuntoPhase,
                    dimens = dimens,
                    currentBet = gameState.currentBet
                )
            }

            ActionButtons(
                actions = gameState.availableActions,
                gamePhase = gameState.gamePhase,
                onActionClick = { action, playerId -> gameViewModel.onAction(action, playerId) },
                selectedCardCount = gameState.selectedCardsForDiscard.size,
                isEnabled = isMyTurn,
                currentPlayerId = gameViewModel.humanPlayerId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = dimens.actionButtonsVerticalOffset,
                        start = 32.dp,
                        end = 32.dp
                    ),
                dimens = dimens
            )

            if (gameState.isSelectingBet) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    BetSelector(
                        onBet = { amount ->
                            gameViewModel.onAction(
                                GameAction.Envido(amount),
                                gameViewModel.humanPlayerId
                            )
                        },
                        onCancel = {
                            gameViewModel.onAction(
                                GameAction.Paso,
                                gameViewModel.humanPlayerId
                            )
                        }
                    )
                }
            }

            if (gameState.gamePhase == GamePhase.GAME_OVER && gameState.winningTeam != null) {
                GameOverOverlay(
                    winnerTeam = gameState.winningTeam!!,
                    ordagoInfo = gameState.ordagoInfo, // <-- Pasa la nueva info
                    players = players, // <-- Pasa la lista de jugadores
                    onNewGameClick = {
                        gameViewModel.onAction(
                            GameAction.NewGame,
                            gameViewModel.humanPlayerId
                        )
                    }
                )
            }

            Box(modifier = Modifier.align(Alignment.Center)) {
                GameEventNotification(event = gameState.event)
            }
        }
        if (gameState.gamePhase == GamePhase.ROUND_OVER && gameState.scoreBreakdown != null) {
            // Este Box se asegura de que el panel aparezca centrado
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 180.dp)
                    .background(Color.Black.copy(alpha = 0.0f)) // Un fondo oscuro para dar énfasis
                    .clickable(enabled = false, onClick = {}),
                contentAlignment = Alignment.BottomCenter
            ) {
                RoundEndOverlay(
                    breakdown = gameState.scoreBreakdown!!,
                    onContinueClick = {
                        gameViewModel.onAction(
                            GameAction.Continue,
                            gameViewModel.humanPlayerId
                        )
                    }
                )
            }
        }
    }
}


@Composable
fun DiscardCountIndicator(count: Int, modifier: Modifier = Modifier) {
    if (count > 0) {
        Text(
            text = "Descarta: $count",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

//A Composable to display a list of action buttons
@Composable
private fun GameActionButton(
    action: GameAction,
    onClick: () -> Unit,
    isEnabled: Boolean,
    dimens: ResponsiveDimens
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
        modifier = Modifier.defaultMinSize(minHeight = dimens.scaleFactor).height(48.dp * dimens.scaleFactor.value)
    ) {
        if (action.iconResId != null) {

            if (action.actionType == ActionType.BET && isEnabled) {
                // Get the TextUnit value (e.g., 15.sp * 1.5 = 22.5.sp)
                val iconSizeSp =
                    dimens.fontSizeMedium

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

        if (action.actionType == ActionType.BET && isEnabled) {
            Text(text = action.displayText, color = secondaryColor, fontSize = dimens.fontSizeMedium)
        } else if (action.actionType == ActionType.DISCARD) {
            Text(text = "Descartar", fontSize = dimens.fontSizeMedium)
        } else {
            Text(text = action.displayText, fontSize = dimens.fontSizeMedium)
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
    modifier: Modifier = Modifier,
    dimens: ResponsiveDimens
) {
    val availableActionsMap = actions.associateBy {
        if (it is GameAction.Envido) GameAction.Envido::class else it::class
    }

    // --- Layout principal que cambia según la fase del juego ---
    Box(
        modifier = modifier.fillMaxWidth().padding(top = dimens.buttonVPadding, bottom = dimens.buttonVPadding),
        contentAlignment = Alignment.BottomCenter
    ) {
        when (gamePhase) {
            GamePhase.MUS -> {
                // FASE MUS: Solo se muestran los botones de Mus
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor.value)
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
                        verticalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor.value),
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
                        verticalArrangement = Arrangement.spacedBy(12.dp * dimens.scaleFactor.value),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pasoAction = GameAction.Paso
                        GameActionButton(
                            action = pasoAction,
                            onClick = { onActionClick(pasoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(pasoAction::class),
                            dimens = dimens
                        )
                        val envidoAction = GameAction.ToggleBetSelector // <-- CAMBIA ESTO
                        GameActionButton(
                            action = envidoAction,
                            onClick = {
                                onActionClick(
                                    envidoAction,
                                    currentPlayerId
                                )
                            }, // Ahora envía la acción de mostrar el selector
                            isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Envido::class),
                            dimens = dimens
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
            .width(dimens.cardWidth * dimens.scaleFactor.value * dimens.scaleFactor.value)
            .aspectRatio(dimens.cardAspectRatio)
            .padding(vertical = 4.dp)
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
            .width(dimens.cardWidth * dimens.scaleFactor.value * dimens.scaleFactor.value)
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

@Composable
private fun PlayerAvatar(
    player: Player,
    isCurrentTurn: Boolean, // This will control the glow
    modifier: Modifier = Modifier,
    isMano: Boolean,
    hasCutMus: Boolean,
    activeGestureResId: Int?,
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


        // Si el jugador es "mano", mostramos el icono superpuesto.
        if (isMano) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mano),
                contentDescription = "Indicador de Mano",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp)
            )
        }

        if (hasCutMus) {
            Icon(
                painter = painterResource(id = R.drawable.ic_cut),
                contentDescription = "Indicador de Corta Mus",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(dimens.avatarSize / 3)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp)
            )
        }
    }
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
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ELLOS: ${score["teamB"] ?: 0}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
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
    // 1. DETERMINAMOS LA ACCIÓN CORRECTA PARA ESTE JUGADOR
    //    Esta variable local 'actionToShow' será la única fuente de verdad para este anuncio.
    var actionToShow = gameState.currentLanceActions[player.id]

    // Solo mostramos la acción transitoria si no hay ya una acción normal para ese jugador
    if (actionToShow == null && gameState.transientAction?.playerId == player.id) {
        actionToShow = gameState.transientAction
    }

    val visible = actionToShow != null

    // Primero, miramos si hay una acción persistente para este jugador en el lance actual.
    val persistentAction = gameState.currentLanceActions[player.id]
    if (persistentAction != null) {
        actionToShow = persistentAction
    }

    // Luego, comprobamos si la acción transitoria (la que cambia de fase) es de este jugador.
    // Si es así, tiene prioridad para mostrarse.
    if (gameState.transientAction?.playerId == player.id) {
        actionToShow = gameState.transientAction
    }

    // 2. LA VISIBILIDAD DEPENDE EXCLUSIVAMENTE DE SI HEMOS ENCONTRADO UNA ACCIÓN

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier.padding(8.dp * dimens.scaleFactor.value)
        ) {
            if (actionToShow?.action is GameAction.ConfirmDiscard) Text(
                text = ("Dame ${gameState.discardCounts[player.id]}"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimens.fontSizeMedium,
                modifier = Modifier.padding(horizontal = 16.dp * dimens.scaleFactor.value, vertical = 8.dp * dimens.scaleFactor.value)
            ) else Text(
                text = actionToShow?.action?.displayText ?: "",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = dimens.fontSizeMedium,
                modifier = Modifier.padding(horizontal = 16.dp * dimens.scaleFactor.value, vertical = 8.dp * dimens.scaleFactor.value)
            )
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
            val translationX = centerOffset * 200f * dimens.scaleFactor.value

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
    Row(modifier = modifier) {
        cards.forEach { card ->
            if (isDebugMode || revealHand) {

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
    Box {
        repeat(cards.size) { index ->
            Box(
                modifier = Modifier.offset(y = (index * 75 * dimens.scaleFactor.value).dp)
            ) {
                if (isDebugMode || revealHand) {
                    GameCard(
                        card = cards[index],
                        isSelected = false,
                        gamePhase = GamePhase.PRE_GAME,
                        isMyTurn = false,
                        onClick = {},
                        modifier = Modifier.graphicsLayer {
                            if (rotate) rotationZ = 90f else rotationZ = 270f
                        },
                        dimens = dimens
                    )
                } else {
                    CardBack(modifier = Modifier.graphicsLayer { rotationZ = 270f }, dimens = dimens)
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
    dimens: ResponsiveDimens

) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerAvatar(player = player,
            isCurrentTurn = isCurrentTurn,
            isMano = isMano, hasCutMus = hasCutMus,
            dimens = dimens,
            activeGestureResId = if (activeGesture?.playerId == player.id) activeGesture.gestureResId else null,)
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
    dimens: ResponsiveDimens
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerAvatar(player = player, isCurrentTurn = isCurrentTurn, isMano = isMano, hasCutMus = hasCutMus, dimens = dimens,activeGestureResId = if (activeGesture?.playerId == player.id) activeGesture.gestureResId else null)
        Box(contentAlignment = Alignment.Center) {
            handContent()
        }
    }
}

@Composable
fun GameOverOverlay(
    winnerTeam: String,
    ordagoInfo: OrdagoInfo?, // <-- Recibe la info del órdago
    players: List<Player>, // <-- Necesita los jugadores para buscar el nombre
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
            modifier = Modifier.padding(bottom = 240.dp)
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

@Composable
fun RoundHistoryDisplay(history: List<LanceResult>, modifier: Modifier = Modifier) {
    // Usamos una Columna para que cada resultado de lance tenga su propia línea.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        history.forEach { result ->
            // Creamos el texto descriptivo basado en el resultado del lance
            val lanceName = result.lance.name.replace('_', ' ')
            val outcomeText = when (result.outcome) {
                "Paso" -> "$lanceName: en Paso"
                "Querido" -> "$lanceName: vale ${result.amount}"
                "No Querido" -> "$lanceName: No Querida"
                else -> ""
            }
            Text(
                text = outcomeText,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// En: GameScreen.kt
// Reemplaza la función RoundEndOverlay entera por esta
@Composable
fun RoundEndOverlay(
    breakdown: ScoreBreakdown,
    onContinueClick: () -> Unit
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
                // Columna para "Nosotros" (sin cambios)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NOSOTROS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    breakdown.teamAScoreDetails.forEach { detail ->
                        Row(
                            modifier = Modifier.width(150.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(detail.reason, color = Color.LightGray, fontSize = 12.sp)
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
                            "+${breakdown.teamAScoreDetails.sumOf { it.points }}",
                            color = Color.Yellow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Columna para "Ellos" (sin cambios)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "ELLOS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    breakdown.teamBScoreDetails.forEach { detail ->
                        Row(
                            modifier = Modifier.width(150.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(detail.reason, color = Color.LightGray, fontSize = 12.sp)
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
                            "+${breakdown.teamBScoreDetails.sumOf { it.points }}",
                            color = Color.Yellow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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

    Card(
        modifier = modifier.width(200.dp * dimens.scaleFactor.value),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            lances.forEach { lance ->
                val isCurrent = (currentPhase == lance)
                val result = history.find { it.lance == lance }
                val wasSkipped = result?.outcome == "Skipped"
                var resultText = ""
                if (isCurrent && currentBet != null) {
                    resultText = "En juego: ${currentBet.amount}"
                } else if (result != null && !wasSkipped) { // No mostramos texto para lances saltados
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
                        color = textColor,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        fontSize = dimens.fontSizeMedium
                    )
                    if (resultText.isNotEmpty()) {
                        Text(
                            text = resultText,
                            color = Color.Gray,
                            fontSize = dimens.fontSizeMedium
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
    onCancel: () -> Unit
) {
    var betAmount by remember { mutableStateOf(2) }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .padding(bottom = 180.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("¿Cuántos quieres envidar?", color = Color.White, fontSize = 18.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(onClick = { if (betAmount > 2) betAmount-- }) {
                    Text("-", fontSize = 24.sp)
                }
                Text(
                    betAmount.toString(),
                    color = Color.Yellow,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = { betAmount++ }) {
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
    dimensions: ResponsiveDimens
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
            }
        }
    }
}

data class ResponsiveDimens(
    val cardWidth: Dp,
    val cardAspectRatio: Float,
    val avatarSize: Dp,
    val handArcTranslationX: Float,
    val handArcTranslationY: Float,
    val handArcRotation: Float,
    val defaultPadding: Dp,
    val smallPadding: Dp,
    val fontSizeLarge: TextUnit,
    val fontSizeMedium: TextUnit,
    val fontSizeSmall: TextUnit,
    val sidePlayerVerticalOffset: Dp,
    val actionButtonsVerticalOffset: Dp,
    val actionbuttonsSize: Dp,
    val buttonVPadding: Dp,
    val buttonHPadding: Dp,
    val scaleFactor: Dp
)
