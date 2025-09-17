// Ubicación: app/src/main/java/com/doselfurioso/musvisto/presentation/MainMenuScreen.kt

package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.R
import androidx.compose.ui.text.style.TextAlign
import com.doselfurioso.musvisto.BuildConfig

@Composable
fun MainMenuScreen(navController: NavController, viewModel: MainMenuViewModel) {
    val hasSavedGame by viewModel.hasSavedGame.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkSavedGame()
    }

    // Definimos los colores del tema del juego para reutilizarlos
    val gameGreenBackground = Color(0xFF006A4E)
    val buttonGreenColor = Color(0xFF6A994E)
    val titleColor = Color.White

    // Usamos un Box para poder poner una imagen de fondo si quisiéramos en el futuro
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Fondo de color verde tapete
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = gameGreenBackground
        ) {}

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app),
                contentDescription = "Logo de Mus Visto",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Lógica de botones condicionales con el nuevo estilo
            if (hasSavedGame) {
                // Si hay partida guardada
                StyledMenuButton(
                    text = "Continuar",
                    onClick = { navController.navigate("game_screen") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                StyledMenuButton(
                    text = "Nueva Partida",
                    onClick = {
                        viewModel.startNewGame()
                        navController.navigate("game_screen")
                    }
                )
            } else {
                // Si no hay partida guardada
                StyledMenuButton(
                    text = "Jugar",
                    onClick = { navController.navigate("game_screen") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StyledMenuButton(
                text = "Señas",
                onClick = { navController.navigate("gestures_screen") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Botón de opciones deshabilitado con estilo coherente
            /*
            Button(
                onClick = { /* Próximamente */ },
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color.DarkGray.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text("Opciones", fontSize = 18.sp)
            }

             */

            Text(
                text = "Versión ${BuildConfig.VERSION_NAME}",
                modifier = Modifier
                    .padding(16.dp),
                color = Color.White.copy(alpha = 0.6f), // Un color sutil
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Creamos un Composable reutilizable para los botones del menú y mantener el código limpio
@Composable
private fun StyledMenuButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF6A994E), // Verde del botón de "Mus"
            contentColor = Color.White
        )
    ) {
        Text(text, fontSize = 18.sp)
    }
}