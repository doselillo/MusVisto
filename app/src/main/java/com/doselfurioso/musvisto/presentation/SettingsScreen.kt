package com.doselfurioso.musvisto.presentation


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    var clickCount by remember { mutableStateOf(0) }

    // El acceso secreto: si el usuario toca 7 veces, navega a la pantalla de logs.
    LaunchedEffect(clickCount) {
        if (clickCount >= 7) {
            navController.navigate("debug_log_screen")
            clickCount = 0 // Resetea el contador
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Mus Visto", style = MaterialTheme.typography.headlineMedium)

            // Texto de la versión con el detector de clics
            Text(
                text = "Versión 1.0.0-beta1",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.clickable { clickCount++ }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Toca 7 veces en el número de versión para abrir el menú de depuración.",
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
        }
    }
}