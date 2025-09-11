// En presentation/MainMenuScreen.kt
package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.runtime.getValue


@Composable
fun MainMenuScreen(navController: NavController, viewModel: MainMenuViewModel) {

    val hasSavedGame by viewModel.hasSavedGame.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkSavedGame()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Mus Visto",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(64.dp))

        // --- LÓGICA DE BOTONES CONDICIONALES ---
        if (hasSavedGame) {
            // Si hay partida guardada, mostramos "Continuar" y "Nueva Partida"
            Button(
                onClick = { navController.navigate("game_screen") },
                modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
            ) {
                Text("Continuar", fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.startNewGame() // Borra los datos guardados
                    navController.navigate("game_screen")
                },
                modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
            ) {
                Text("Nueva Partida", fontSize = 18.sp)
            }
        } else {
            // Si no hay partida, solo mostramos "Jugar"
            Button(
                onClick = { navController.navigate("game_screen") },
                modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
            ) {
                Text("Jugar", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { navController.navigate("gestures_screen") },
            modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
        ) {
            Text("Ver Señas", fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { /* Próximamente */ },
            enabled = false,
            modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
        ) {
            Text("Opciones", fontSize = 18.sp)
        }
    }
}