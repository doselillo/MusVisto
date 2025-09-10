// En presentation/GesturesScreen.kt
package com.doselfurioso.musvisto.presentation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.R

// Estructura de datos para una seña
data class GestureInfo(
    @DrawableRes val imageResId: Int,
    val name: String,
    val description: String
)

// Lista con todas las señas de tu juego
val allGestures = listOf(
    GestureInfo(R.drawable.reyes_2, "Dos Reyes", "Seña de Pares. Indica que se tienen dos reyes."),
    GestureInfo(R.drawable.reyes_3, "Tres Reyes", "Seña de Medias. Indica que se tienen tres reyes."),
    GestureInfo(R.drawable.ases_2, "Dos Ases", "Seña de Pares. Indica que se tienen dos ases."),
    GestureInfo(R.drawable.ases_3, "Tres Ases", "Seña de Medias. Indica que se tienen tres ases."),
    GestureInfo(R.drawable.sena_31, "31 de Juego", "Seña de Juego. La mejor jugada posible."),
    GestureInfo(R.drawable.ciega, "Ciega", "Indica que no se tiene ni Pares ni Juego."),
    GestureInfo(R.drawable.duples_altos, "Duples Altos", "Seña de Duples. Los dos pares son Sota, Caballo o Rey."),
    GestureInfo(R.drawable.duples_bajos, "Duples Bajos", "Seña de Duples. Al menos un par es inferior a Sota.")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesturesScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guía de Señas") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(allGestures) { gesture ->
                Card(elevation = CardDefaults.cardElevation(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = gesture.imageResId),
                            contentDescription = gesture.name,
                            modifier = Modifier
                                .size(64.dp)
                                .padding(end = 16.dp)
                        )
                        Column {
                            Text(text = gesture.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(text = gesture.description, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}