package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

private val BackgroundGreen = Color(0xFF006A4E)

/**
 * Pantalla de PARTIDA online (Fase 3c — Slice B). Reusa la MISMA mesa que el modo local
 * ([GameTable]), alimentada por la vista redactada del host (adaptada en
 * [OnlineGameViewModel.displayState]). Cada jugador se ve a sí mismo abajo (rotación por
 * `mySeatId`). Online NO hay botones de seña/pausa; un "Salir" superpuesto abandona la
 * partida.
 */
@Composable
fun OnlineGameScreen(navController: NavController, viewModel: OnlineGameViewModel) {
    val state by viewModel.displayState.collectAsState()
    val offlineSeats by viewModel.offlineSeats.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundGreen)
    ) {
        val view = state
        if (view == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("Esperando al host…", color = Color.White, fontSize = 16.sp)
            }
        } else {
            GameTable(
                gameState = view,
                localSeatId = viewModel.mySeatId,
                isDebugMode = false,
                onAction = { action, _ -> viewModel.onAction(action) },
                onCardSelected = { viewModel.onCardSelected(it) },
                hasShowableGesture = { viewModel.hasShowableGesture(it) },
                // Fase 4.3: el humano emite seña online (SÍ botón seña). Sin pausa (usa "Salir").
                showPauseButton = false,
                // Presencia: marca en la mesa qué humanos están caídos (indicador en su avatar).
                offlineSeatIds = offlineSeats
            )
            TextButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(8.dp)
            ) {
                Text("Salir", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            }
        }
    }
}
