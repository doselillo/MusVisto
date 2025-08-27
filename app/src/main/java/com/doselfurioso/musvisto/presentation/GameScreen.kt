package com.doselfurioso.musvisto.presentation

import android.annotation.SuppressLint
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.draw.rotate
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.logic.MusGameLogic
import com.doselfurioso.musvisto.model.ActionType
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameEvent
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.LanceResult
import com.doselfurioso.musvisto.model.LastActionInfo
import com.doselfurioso.musvisto.model.Player
import kotlinx.coroutines.delay
import java.util.Collections.rotate
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

//Composable for a single card without the Surface wrapper
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
            .width(70.dp)
            .aspectRatio(0.7f)
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

//Composable for the back of a card without the Surface wrapper
@Composable
fun CardBack(modifier: Modifier = Modifier) { // <-- ADD THIS PARAMETER
    Image(
        painter = painterResource(id = R.drawable.card_back),
        contentDescription = "Card back",
        // Apply the passed-in modifier here, then add our specific ones
        modifier = modifier
            .height(70.dp)
            .aspectRatio(0.7f)
            .padding(vertical = 4.dp)
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
    onCardClick: (CardData) -> Unit
) {

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        val cardCount = cards.size
        cards.forEachIndexed { index, card ->
            val isSelected = card in selectedCards

            val centerOffset = index - (cardCount - 1) / 2f
            val rotation = centerOffset * 5f
            val translationY = abs(centerOffset) * -15f
            val translationX = centerOffset * 150f

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
    revealHand: Boolean
) {
    Row(modifier = modifier) {
        cards.forEach { card ->
            if (isDebugMode || revealHand) {

                GameCard(
                    card = card,
                    isSelected = false,
                    gamePhase = GamePhase.PRE_GAME,
                    isMyTurn = false,
                    onClick = {}
                )
            } else {

                CardBack()
            }
        }
    }
}

@Composable
fun SideOpponentHandStacked(modifier: Modifier, cards: List<CardData>, isDebugMode: Boolean, revealHand: Boolean, rotate: Boolean = false) {
    Box {
        repeat(cards.size) { index ->
            Box(
                modifier = Modifier.offset(y = (index * 50).dp)
            ) {
                if (isDebugMode || revealHand) {
                    GameCard(
                        card = cards[index],
                        isSelected = false,
                        gamePhase = GamePhase.PRE_GAME,
                        isMyTurn = false,
                        onClick = {},
                        modifier = Modifier.graphicsLayer { if (rotate) rotationZ = 90f else rotationZ = 270f }
                    )
                } else {
                    CardBack(modifier = Modifier.graphicsLayer { rotationZ = 270f })
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
    handContent: @Composable () -> Unit

) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerAvatar(player = player, isCurrentTurn = isCurrentTurn, isMano = isMano)
        handContent()
    }
}

@Composable
fun HorizontalPlayerArea(
    player: Player,
    isCurrentTurn: Boolean,
    isMano: Boolean,
    handContent: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerAvatar(player = player, isCurrentTurn = isCurrentTurn, isMano = isMano)
        Box(contentAlignment = Alignment.Center) {
            handContent()
        }
    }
}

@Composable
fun GameScreen(
    gameViewModel: GameViewModel = viewModel()
) {
    val gameState by gameViewModel.gameState.collectAsState()
    val isDebugMode by gameViewModel.isDebugMode.collectAsState()
    val players = gameState.players
    val gameLogic: MusGameLogic = hiltViewModel<GameViewModel>().gameLogic

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
            Box(Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 150.dp)) {
                ActionAnnouncement(rivalLeft, gameState)
            }
            Box(Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 150.dp)) {
                ActionAnnouncement(rivalRight, gameState)
            }
            Box(Modifier
                .align(Alignment.TopStart)
                .padding(start = 50.dp, top = 110.dp)) {
                ActionAnnouncement(partner, gameState)
            }
            Box(Modifier
                .align(Alignment.BottomStart)
                .padding(start = 36.dp, bottom = 140.dp)) {
                ActionAnnouncement(player, gameState)
            }
            // ÁREA DEL COMPAÑERO (ARRIBA) - Vertical
            Box(modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)) {
                HorizontalPlayerArea(
                    player = partner,
                    isCurrentTurn = gameState.currentTurnPlayerId == partner.id,
                    isMano = gameState.manoPlayerId == partner.id,
                    handContent = { PartnerHand(modifier = Modifier.graphicsLayer { rotationZ = 180f },
                        cards = partner.hand.sortedByDescending { it.rank.value },
                        isDebugMode = isDebugMode,
                        revealHand = gameState.revealAllHands) }
                )
            }

            // ÁREA DEL RIVAL IZQUIERDO - Horizontal
            Box(modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp, bottom = 280.dp)) {
                VerticalPlayerArea(
                    player = rivalLeft,
                    isCurrentTurn = gameState.currentTurnPlayerId == rivalLeft.id,
                    isMano = gameState.manoPlayerId == rivalLeft.id,
                    handContent = { SideOpponentHandStacked(
                        modifier = Modifier
                            .padding(16.dp)
                            .graphicsLayer { rotationX = 90f },
                        cards = rivalLeft.hand.sortedByDescending { it.rank.value },
                        isDebugMode = isDebugMode,
                        revealHand = gameState.revealAllHands,
                        rotate = true) }
                )
            }

            // ÁREA DEL RIVAL DERECHO - Horizontal
            Box(modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, bottom = 280.dp)) {
                VerticalPlayerArea(
                    player = rivalRight,
                    isCurrentTurn = gameState.currentTurnPlayerId == rivalRight.id,
                    isMano = gameState.manoPlayerId == rivalRight.id,
                    handContent = { SideOpponentHandStacked(
                        modifier = Modifier
                            .graphicsLayer { rotationZ = 180f },
                        cards = rivalRight.hand.sortedBy { it.rank.value },
                        isDebugMode = isDebugMode,
                        revealHand = gameState.revealAllHands,
                        rotate = false) }
                )
            }

            // ÁREA DEL JUGADOR PRINCIPAL (ABAJO) - Vertical
            Box(modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 32.dp)) {
                HorizontalPlayerArea(
                    player = player,
                    isCurrentTurn = isMyTurn,
                    isMano = gameState.manoPlayerId == player.id,
                    handContent = {
                        PlayerHandArc(
                            cards = player.hand.sortedByDescending { it.rank.value },
                            selectedCards = gameState.selectedCardsForDiscard,
                            gamePhase = gameState.gamePhase,
                            isMyTurn = isMyTurn,
                            onCardClick = { card -> gameViewModel.onCardSelected(card) }
                        )
                    }
                )
            }

            // INFO CENTRAL Y BOTONES
            // Dentro del Column central en GameScreen
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 200.dp, start = 120.dp, end = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Scoreboard(score = gameState.score)
                Spacer(modifier = Modifier.height(8.dp))
                ActionLogDisplay(log = gameState.actionLog, players = players, gameState.gamePhase)
                RoundHistoryDisplay(history = gameState.roundHistory)
            }

            ActionButtons(
                actions = gameState.availableActions,
                gamePhase = gameState.gamePhase, // <-- Pasa la fase del juego
                onActionClick = { action, playerId -> gameViewModel.onAction(action, playerId) },
                selectedCardCount = gameState.selectedCardsForDiscard.size,
                isEnabled = isMyTurn,
                currentPlayerId = gameViewModel.humanPlayerId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 180.dp, start = 32.dp, end = 32.dp) // Añadimos padding lateral
            )


            if (gameState.gamePhase == GamePhase.GAME_OVER && gameState.winningTeam != null) {
                GameOverOverlay(
                    winner = gameState.winningTeam!!,
                    onNewGameClick = { gameViewModel.onAction(GameAction.NewGame, gameViewModel.humanPlayerId) }
                )
            }

            Box(modifier = Modifier.align(Alignment.Center)) {
                GameEventNotification(event = gameState.event)
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
    isEnabled: Boolean
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
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        if (action.iconResId != null) {

            if (action.actionType == ActionType.BET && isEnabled) Icon(
                painter = painterResource(id = action.iconResId),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = secondaryColor
            ) else Icon(
                painter = painterResource(id = action.iconResId),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize))

            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }

        if (action.actionType == ActionType.BET && isEnabled) {
            Text(text = action.displayText, color = secondaryColor)
        } else if (action.actionType == ActionType.DISCARD) {
            Text(text = "Descartar")
        } else {
            Text(text = action.displayText)
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
    modifier: Modifier = Modifier
) {
    val availableActionsMap = actions.associateBy {
        if (it is GameAction.Envido) GameAction.Envido::class else it::class
    }

    // --- Manejo para fin de ronda/partida ---
    val roundEndActions = actions.filter { it is GameAction.Continue || it is GameAction.NewGame }
    if (roundEndActions.isNotEmpty()) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            roundEndActions.forEach { action ->
                GameActionButton(action = action, onClick = { onActionClick(action, currentPlayerId) }, isEnabled = true)
            }
        }
        return
    }

    // --- Layout principal que cambia según la fase del juego ---
    Box(modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd) {
        when (gamePhase) {
            GamePhase.MUS -> {
                // FASE MUS: Solo se muestran los botones de Mus
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GameActionButton(
                        action = GameAction.Mus,
                        onClick = { onActionClick(GameAction.Mus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Mus::class)
                    )
                    GameActionButton(
                        action = GameAction.NoMus,
                        onClick = { onActionClick(GameAction.NoMus, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.NoMus::class)
                    )
                }
            }
            GamePhase.DISCARD -> {
                // FASE DESCARTE: Solo se muestra el botón de descartar
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    val discardAction = GameAction.ConfirmDiscard
                    GameActionButton(
                        action = discardAction,
                        onClick = { onActionClick(discardAction, currentPlayerId) },
                        isEnabled = isEnabled && selectedCardCount > 0
                    )
                }
            }
            else -> {
                // FASES DE APUESTA (GRANDE, CHICA, PARES, JUEGO): Layout fijo de dos columnas

                // Columna Izquierda: Respuestas
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Bottom // <-- ESTA ES LA LÍNEA A CAMBIAR

                )  {
                    Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val quieroAction = GameAction.Quiero
                    GameActionButton(
                        action = quieroAction,
                        onClick = { onActionClick(quieroAction, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(quieroAction::class)
                    )
                    val noQuieroAction = GameAction.NoQuiero
                    GameActionButton(
                        action = noQuieroAction,
                        onClick = { onActionClick(noQuieroAction, currentPlayerId) },
                        isEnabled = isEnabled && availableActionsMap.containsKey(noQuieroAction::class)
                    )
                }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pasoAction = GameAction.Paso
                        GameActionButton(
                            action = pasoAction,
                            onClick = { onActionClick(pasoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(pasoAction::class)
                        )
                        val envidoAction = GameAction.Envido(2)
                        GameActionButton(
                            action = envidoAction,
                            onClick = { onActionClick(envidoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(GameAction.Envido::class)
                        )
                        val ordagoAction = GameAction.Órdago
                        GameActionButton(
                            action = ordagoAction,
                            onClick = { onActionClick(ordagoAction, currentPlayerId) },
                            isEnabled = isEnabled && availableActionsMap.containsKey(ordagoAction::class)
                        )
                    }
                }


                // Columna Derecha: Acciones

            }
        }
    }
}

@Composable
fun PlayerAvatar(
    player: Player,
    isCurrentTurn: Boolean, // This will control the glow
    modifier: Modifier = Modifier,
    isMano: Boolean
) {
    val borderColor = if (isCurrentTurn) Color.Yellow else Color.Transparent


    Box(
        modifier = modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = player.avatarResId),
            contentDescription = "Avatar of ${player.name}",
            modifier = Modifier
                .fillMaxSize() // El avatar ocupa todo el Box
                .clip(CircleShape)
                .border(4.dp, borderColor, CircleShape)
        )

        // Si el jugador es "mano", mostramos el icono superpuesto.
        if (isMano) {
            Icon(
                painter = painterResource(id = R.drawable.ic_mano),
                contentDescription = "Indicador de Mano",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd) // Lo colocamos en la esquina inferior derecha.
                    .size(24.dp)
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
// --- REEMPLAZA EL ANTIGUO LanceInfo POR ESTE ---
@Composable
fun ActionLogDisplay(
    log: List<LastActionInfo>,
    players: List<Player>,
    gamePhase: GamePhase, // <-- AÑADE ESTE PARÁMETRO
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.defaultMinSize(minHeight = 120.dp), // Aumentamos un poco el tamaño
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp), // Espacio entre elementos
        ) {
            // Título del Lance
            Text(
                if (gamePhase == GamePhase.DISCARD) "DESCARTAR" else gamePhase.name.replace('_', ' '),
                color = Color.Yellow,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            // Divisor
            Box(
                modifier = Modifier
                    .height(1.dp)
                    .fillMaxWidth(0.8f)
                    .background(Color.Gray)
            )

            // Log de acciones
            if (log.isEmpty()) {
                Text("Esperando acción...", color = Color.Gray, fontSize = 14.sp)
            } else {
                log.forEach { actionInfo ->
                    val playerName = players.find { it.id == actionInfo.playerId }?.name ?: ""
                    Text(
                        text = "$playerName: ${actionInfo.action.displayText}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ActionAnnouncement(
    player: Player,
    gameState: GameState
) {
    // 1. DETERMINAMOS LA ACCIÓN CORRECTA PARA ESTE JUGADOR
    //    Esta variable local 'actionToShow' será la única fuente de verdad para este anuncio.
    var actionToShow: LastActionInfo? = null

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
    val visible = actionToShow != null

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier.padding(8.dp)
        ) {
            if (actionToShow?.action is GameAction.ConfirmDiscard) Text(
                text = ("Dame ${gameState.discardCounts[player.id]}"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) else Text(
                text = actionToShow?.action?.displayText ?: "",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}


@Composable
fun GameOverOverlay(winner: String, onNewGameClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (winner == "teamA") "¡HAS GANADO!" else "HAS PERDIDO",
                color = Color.Yellow,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onNewGameClick) {
                Text(text = "Jugar de Nuevo", fontSize = 24.sp)
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