package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/**
 * Pantalla de Opciones (#29 Fase 2). De momento solo el ajuste de la vaca
 * (mejor de N chicos), pero estructurada para crecer: cada ajuste futuro
 * (treses como reyes, etc.) es otra sección con su propio [SettingSelector].
 * Los cambios se guardan al instante y se aplican a la PRÓXIMA partida nueva.
 */
@Composable
fun OptionsScreen(navController: NavController, viewModel: MainMenuViewModel) {
    val settings by viewModel.settings.collectAsState()
    // Acento (botones) a juego con el tapete: aquí el fondo ES el tapete, así que
    // fondo y botones cambian juntos al elegir color (#36).
    val tableAccent = TableTheme.accentFor(settings.tableColor)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // El fondo usa el tapete elegido → al tocar un color se ve al instante.
        Surface(modifier = Modifier.fillMaxSize(), color = TableTheme.colorFor(settings.tableColor)) {}

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Opciones",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(40.dp))

            SettingSelector(
                title = "Vaca (partida)",
                options = listOf(3 to "Mejor de 3", 5 to "Mejor de 5"),
                selected = settings.bestOfChicos,
                onSelect = viewModel::setBestOfChicos,
                accent = tableAccent
            )

            Spacer(modifier = Modifier.height(28.dp))

            TableColorSelector(
                selectedKey = settings.tableColor,
                onSelect = viewModel::setTableColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Se aplica a la próxima partida nueva.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tableAccent,
                    contentColor = Color.White
                )
            ) {
                Text("Volver", fontSize = 18.sp)
            }
        }
    }
}

/** Un ajuste con varias opciones excluyentes (la seleccionada va resaltada). */
@Composable
private fun SettingSelector(
    title: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    accent: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Button(
                    onClick = { onSelect(value) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) accent else Color.Black.copy(alpha = 0.3f),
                        contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    // Peso de fuente constante: la selección se marca por color
                    // (evita que el botón hermano salte al cambiar Normal<->Bold).
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Selector del color de tapete (#36). El fondo de la pantalla refleja la
 * elección al instante (preview en vivo); se aplica a la mesa en la próxima
 * partida nueva (una partida en curso conserva su color).
 */
@Composable
private fun TableColorSelector(selectedKey: String, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Tapete",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TableTheme.options.forEach { option ->
                val isSelected = option.key == selectedKey
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(option.color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onSelect(option.key) }
                )
            }
        }
    }
}
