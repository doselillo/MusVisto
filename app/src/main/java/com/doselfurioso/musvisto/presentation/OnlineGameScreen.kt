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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit

private val BackgroundGreen = Color(0xFF006A4E)
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
                onLeave = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun GameContent(view: GameState, mySeatId: String, isHost: Boolean, onLeave: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
        Spacer(Modifier.height(16.dp))

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
