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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.model.RoomSeat
import com.doselfurioso.musvisto.model.RoomSnapshot
import com.doselfurioso.musvisto.model.Rooms

private val BackgroundGreen = Color(0xFF006A4E)
private val AccentGreen = Color(0xFF6A994E)
private val SeatCardGreen = Color(0xFF0A7A5C)
private val TeamAColor = Color(0xFF5B8FD6)
private val TeamBColor = Color(0xFFD08770)

@Composable
fun OnlineLobbyScreen(navController: NavController, viewModel: LobbyViewModel) {
    val state by viewModel.state.collectAsState()

    // Cuando el host marca la sala como "playing", TODOS los clientes (host
    // incluido) lo ven por el observador en vivo y navegan a la partida. Se saca
    // el lobby de la pila (popUpTo inclusive) para no re-disparar al recomponer.
    val playing = state.room?.status == Rooms.STATUS_PLAYING
    val handle = state.myHandle
    LaunchedEffect(playing, handle?.roomId, handle?.seatId) {
        if (playing && handle != null) {
            navController.navigate("online_game/${handle.roomId}/${handle.seatId}/${state.isHost}") {
                popUpTo("online_lobby") { inclusive = true }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGreen),
        contentAlignment = Alignment.Center
    ) {
        when (state.phase) {
            LobbyPhase.ENTRY -> EntryContent(
                error = state.error,
                onCreate = viewModel::createRoom,
                onJoin = viewModel::joinRoom,
                onBack = { navController.popBackStack() }
            )

            LobbyPhase.CONNECTING -> CircularProgressIndicator(color = Color.White)

            LobbyPhase.IN_ROOM -> RoomContent(
                state = state,
                viewModel = viewModel,
                onLeave = {
                    viewModel.leaveRoom()
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
private fun EntryContent(
    error: String?,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Jugar online", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))

        LobbyButton(text = "Crear sala", onClick = onCreate)
        Spacer(Modifier.height(28.dp))
        Text("o únete con un código", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(CODE_MAX_LENGTH) },
            singleLine = true,
            label = { Text("Código") },
            colors = lobbyFieldColors()
        )
        Spacer(Modifier.height(12.dp))
        LobbyButton(text = "Unirse", onClick = { onJoin(code) })

        if (error != null) {
            Spacer(Modifier.height(20.dp))
            Text(error, color = Color(0xFFFFD2CC), fontSize = 14.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(40.dp))
        TextButton(onClick = onBack) {
            Text("Volver", color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun RoomContent(state: LobbyUiState, viewModel: LobbyViewModel, onLeave: () -> Unit) {
    val room = state.room ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Código de sala", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Text(room.code, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        room.seats.forEach { seat ->
            SeatRow(
                seat = seat,
                isMySeat = seat.seatId == state.mySeatId,
                isHostSeat = seat.uid != null && seat.uid == room.hostUid,
                amHost = state.isHost,
                onAddAi = { viewModel.addAi(seat.seatId) },
                onCycle = { viewModel.cycleArchetype(seat.seatId) },
                onClear = { viewModel.clearSeat(seat.seatId) }
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.weight(1f))

        val mySeat = room.seats.firstOrNull { it.seatId == state.mySeatId }
        if (mySeat != null && !mySeat.isAi) {
            LobbyButton(
                text = if (mySeat.ready) "Anular listo" else "Estoy listo",
                onClick = viewModel::toggleReady
            )
            Spacer(Modifier.height(10.dp))
        }
        if (state.isHost) {
            LobbyButton(
                text = "Empezar",
                onClick = viewModel::startMatch,
                enabled = room.allReady && room.status == Rooms.STATUS_LOBBY
            )
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = onLeave) {
            Text("Salir de la sala", color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SeatRow(
    seat: RoomSeat,
    isMySeat: Boolean,
    isHostSeat: Boolean,
    amHost: Boolean,
    onAddAi: () -> Unit,
    onCycle: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SeatCardGreen, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(if (seat.team == "teamA") TeamAColor else TeamBColor, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(seatTitle(seat, isMySeat, isHostSeat), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(seatSubtitle(seat), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        }
        SeatTrailing(seat, amHost, onAddAi, onCycle, onClear)
    }
}

@Composable
private fun SeatTrailing(
    seat: RoomSeat,
    amHost: Boolean,
    onAddAi: () -> Unit,
    onCycle: () -> Unit,
    onClear: () -> Unit
) {
    when {
        amHost && seat.isEmpty -> TextButton(onClick = onAddAi) { Text("+ IA", color = Color.White) }
        amHost && seat.isAi -> Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCycle) { Text("‹ ${archetypeLabel(seat)} ›", color = Color.White) }
            TextButton(onClick = onClear) { Text("✕", color = Color(0xFFFFD2CC)) }
        }
        seat.ready || seat.isAi -> Text("✓", color = Color(0xFF9BE29B), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        else -> Text("…", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
    }
}

private fun seatTitle(seat: RoomSeat, isMySeat: Boolean, isHostSeat: Boolean): String {
    val base = when {
        seat.isAi -> "IA"
        seat.uid != null -> seat.displayName.ifBlank { "Jugador" }
        else -> "Vacío"
    }
    val tags = buildList {
        if (isMySeat) add("tú")
        if (isHostSeat) add("host")
    }
    return if (tags.isEmpty()) base else "$base (${tags.joinToString(", ")})"
}

private fun seatSubtitle(seat: RoomSeat): String = when {
    seat.isAi -> "IA · ${archetypeLabel(seat)}"
    seat.uid != null -> if (seat.ready) "Listo" else "Esperando…"
    else -> "Asiento libre"
}

private fun archetypeLabel(seat: RoomSeat): String = seat.archetype ?: "EQUILIBRADO"

@Composable
private fun LobbyButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.White)
    ) {
        Text(text, fontSize = 18.sp)
    }
}

@Composable
private fun lobbyFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Color.White,
    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
    focusedLabelColor = Color.White,
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
    cursorColor = Color.White
)

private const val CODE_MAX_LENGTH = 6
