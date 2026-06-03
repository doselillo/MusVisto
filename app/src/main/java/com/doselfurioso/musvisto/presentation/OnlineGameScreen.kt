package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit

private val BackgroundGreen = Color(0xFF006A4E)
private val AccentGreen = Color(0xFF6A994E)
private val PanelGreen = Color(0xFF0A7A5C)
private val TeamAColor = Color(0xFF5B8FD6)
private val TeamBColor = Color(0xFFD08770)
private val TurnGold = Color(0xFFFFE9A8)

/**
 * Pantalla de PARTIDA online (Fase 3b, sub-paso 1: SOLO LECTURA). Renderiza la
 * vista redactada que el host publica para este asiento (marcador, fase, turno,
 * la mesa y la mano propia). Aún SIN botones de acción: este checkpoint valida
 * que el bucle host↔cliente fluye en vivo sobre Firebase (Paso 2 añade enviar
 * comandos). Ver [OnlineGameViewModel].
 */
@Composable
fun OnlineGameScreen(navController: NavController, viewModel: OnlineGameViewModel) {
    val view by viewModel.view.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGreen),
        contentAlignment = Alignment.Center
    ) {
        val current = view
        if (current == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("Esperando al host…", color = Color.White, fontSize = 16.sp)
            }
        } else {
            GameContent(
                view = current,
                mySeatId = viewModel.mySeatId,
                isHost = viewModel.isHost,
                onSend = viewModel::send,
                onLeave = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun GameContent(
    view: GameState,
    mySeatId: String,
    isHost: Boolean,
    onSend: (GameCommand) -> Unit,
    onLeave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            if (isHost) "Partida online (host)" else "Partida online",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        ScoreBoard(view)
        Spacer(Modifier.height(12.dp))

        val turnName = view.players.firstOrNull { it.id == view.currentTurnPlayerId }?.name
        Text(
            "${phaseLabel(view.gamePhase)} · Turno: ${turnName ?: "—"}",
            color = TurnGold,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))
        ActionAnnouncement(view)

        view.players.forEach { player ->
            SeatLine(
                player = player,
                isMe = player.id == mySeatId,
                isTurn = player.id == view.currentTurnPlayerId,
                isMano = player.id == view.manoPlayerId
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text("Tu mano ($mySeatId)", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        HandRow(view.players.firstOrNull { it.id == mySeatId }?.hand.orEmpty())

        Spacer(Modifier.height(20.dp))
        ActionBar(
            commands = view.availableCommands,
            isMyTurn = view.currentTurnPlayerId == mySeatId,
            onSend = onSend
        )

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onLeave) {
            Text("Salir", color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun ScoreBoard(view: GameState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TeamScore("Nosotros", view.score["teamA"] ?: 0, TeamAColor, Modifier.weight(1f))
        TeamScore("Ellos", view.score["teamB"] ?: 0, TeamBColor, Modifier.weight(1f))
    }
    val chicosA = view.chicosWon["teamA"] ?: 0
    val chicosB = view.chicosWon["teamB"] ?: 0
    if (chicosA > 0 || chicosB > 0) {
        Spacer(Modifier.height(6.dp))
        Text("Chicos: $chicosA – $chicosB", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
    }
}

@Composable
private fun TeamScore(label: String, score: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(PanelGreen, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("$score", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionAnnouncement(view: GameState) {
    val last = view.lastActionView ?: return
    val who = view.players.firstOrNull { it.id == last.seatId }?.name ?: last.seatId
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelGreen, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$who  ·  ${commandLabel(last.command)}",
            color = TurnGold,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SeatLine(player: Player, isMe: Boolean, isTurn: Boolean, isMano: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isTurn) PanelGreen else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (player.team == "teamA") TeamAColor else TeamBColor, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        val tags = buildList {
            if (isMe) add("tú")
            if (player.isAi) add("IA")
            if (isMano) add("mano")
        }
        val suffix = if (tags.isEmpty()) "" else " (${tags.joinToString(", ")})"
        Text(
            player.name + suffix,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (isTurn) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun HandRow(hand: List<Card>) {
    if (hand.isEmpty()) {
        Text("—", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        hand.forEach { card -> CardChip(card) }
    }
}

@Composable
private fun CardChip(card: Card) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            rankLabel(card.rank),
            color = Color(0xFF1A1A1A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(suitLabel(card.suit), color = Color(0xFF555555), fontSize = 11.sp)
    }
}

@Composable
private fun ActionBar(commands: List<GameCommand>, isMyTurn: Boolean, onSend: (GameCommand) -> Unit) {
    if (!isMyTurn) {
        Text("Esperando al rival…", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        return
    }
    // Paso 2: solo acciones de UN TOQUE. El descarte (multi-selección) y la
    // cantidad de envite a medida (BetSelector) llegan en el 2b.
    val supported = commands.filter { it !is GameCommand.Discard }
    if (supported.isEmpty()) {
        if (commands.any { it is GameCommand.Discard }) {
            Text(
                "Descarte: en el siguiente paso. Corta el Mus para jugar la mano.",
                color = TurnGold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        supported.chunked(2).forEach { rowCommands ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCommands.forEach { command -> ActionButton(command, onSend) }
            }
        }
    }
}

@Composable
private fun ActionButton(command: GameCommand, onSend: (GameCommand) -> Unit) {
    Button(
        onClick = { onSend(command) },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.White)
    ) {
        Text(commandLabel(command), fontSize = 15.sp)
    }
}

private fun phaseLabel(phase: GamePhase): String = when (phase) {
    GamePhase.PRE_GAME -> "Preparando"
    GamePhase.MUS -> "Mus"
    GamePhase.DISCARD -> "Descarte"
    GamePhase.GRANDE -> "Grande"
    GamePhase.CHICA -> "Chica"
    GamePhase.PARES_CHECK, GamePhase.PARES -> "Pares"
    GamePhase.JUEGO_CHECK, GamePhase.JUEGO -> "Juego"
    GamePhase.ROUND_OVER -> "Fin de ronda"
    GamePhase.GAME_OVER -> "Fin de partida"
}

private fun rankLabel(rank: Rank): String = when (rank) {
    Rank.AS -> "As"
    Rank.DOS -> "2"
    Rank.TRES -> "3"
    Rank.CUATRO -> "4"
    Rank.CINCO -> "5"
    Rank.SEIS -> "6"
    Rank.SIETE -> "7"
    Rank.SOTA -> "Sota"
    Rank.CABALLO -> "Caballo"
    Rank.REY -> "Rey"
}

private fun suitLabel(suit: Suit): String = when (suit) {
    Suit.OROS -> "Oros"
    Suit.COPAS -> "Copas"
    Suit.ESPADAS -> "Espadas"
    Suit.BASTOS -> "Bastos"
}

private fun commandLabel(command: GameCommand): String = when (command) {
    GameCommand.Mus -> "Mus"
    GameCommand.NoMus -> "No hay mus"
    GameCommand.Pass -> "Paso"
    GameCommand.Accept -> "Quiero"
    GameCommand.Decline -> "No quiero"
    GameCommand.Ordago -> "¡Órdago!"
    GameCommand.Continue -> "Continuar"
    GameCommand.NewGame -> "Nueva partida"
    GameCommand.Tengo -> "Tengo"
    GameCommand.NoTengo -> "No tengo"
    is GameCommand.Bet -> "Envido ${command.amount}"
    is GameCommand.Discard -> "Descartar"
}
