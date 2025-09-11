// Ubicación: app/src/main/java/com/doselfurioso/musvisto/presentation/GesturesScreen.kt

package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.* // Usar Material3 components
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.Sign
import com.doselfurioso.musvisto.ui.theme.MusVistoTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GesturesScreen(navController: NavController) {

    // Colores del tema del juego
    // Colores del tema del juego
    val gameGreenBackground = Color(0xFF006A4E) // Fondo verde oscuro del tapete
    // --- CAMBIO CLAVE AQUÍ: Color de la tarjeta más neutro ---
    val cardColor = Color(0xFFF0E6D6) // Un beige/crema claro (como pergamino)
    val cardBorderColor = Color(0xFFC0A06C) // Un color dorado/marrón para el borde
    val textColorPrimary = Color(0xFF333333) // Texto oscuro para contrastar con el beige
    val textColorSecondary = Color(0xFF666666)// Texto gris oscuro para la descripción

    val signs = listOf(
        Sign("1", "Dos Reyes", "Indica que se tienen dos reyes.", R.drawable.reyes_2),
        Sign("2", "Tres Reyes", "Indica que se tienen tres reyes.", R.drawable.reyes_3),
        Sign("3", "Dos Ases", "Indica que se tienen dos ases.", R.drawable.ases_2),
        Sign("4", "Tres Ases", "Indica que se tienen tres ases.", R.drawable.ases_3),
        Sign("5", "31 de Juego", "Indica Juego de 31.", R.drawable.sena_31),
        Sign("6", "Ciega", "Indica que no se tiene ni Pares ni Juego.", R.drawable.ciega),
        Sign("7", "Duples Altos", "Indica doble pareja, al menos una de reyes.", R.drawable.duples_altos),
        // Puedes añadir más señas aquí
        Sign("8", "Duples Bajos", "Indica doble pareja, ninguna de ellas de reyes.", R.drawable.duples_bajos)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Guía de Señas",
                        color = Color.White, // Título siempre blanco para contraste con TopBar verde
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White // Icono de flecha siempre blanco
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = gameGreenBackground,
                    titleContentColor = Color.White // Asegura el color del título en la TopAppBar
                )
            )
        },
        containerColor = gameGreenBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp), // Aumentar un poco el padding general
            verticalArrangement = Arrangement.spacedBy(10.dp) // Aumentar espacio entre tarjetas
        ) {
            items(signs) { sign ->
                SignItem(
                    sign = sign,
                    cardColor = cardColor,
                    textColorPrimary = textColorPrimary,
                    textColorSecondary = textColorSecondary,
                    cardBorderColor = cardBorderColor // Pasa el color del borde
                )
            }
        }
    }
}

@Composable
fun SignItem(sign: Sign, cardColor: Color, textColorPrimary: Color, textColorSecondary: Color, cardBorderColor: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Opcional: mostrar más detalles de la seña al tocarla */ },
        shape = RoundedCornerShape(12.dp), // Esquinas un poco más redondeadas
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp), // Aumentar un poco la sombra
        border = BorderStroke(1.5.dp, cardBorderColor) // Borde sutil
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp), // Aumentar padding dentro de la tarjeta
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono de la seña
            Image(
                painter = painterResource(id = sign.iconResId),
                contentDescription = sign.name,
                modifier = Modifier.size(52.dp) // Tamaño del icono un poco más grande
            )
            Spacer(modifier = Modifier.width(20.dp)) // Espacio entre icono y texto
            Column {
                // Nombre de la seña
                Text(
                    text = sign.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp, // Tamaño de fuente un poco más grande
                    color = textColorPrimary
                )
                // Descripción de la seña
                Text(
                    text = sign.description,
                    fontSize = 15.sp, // Tamaño de fuente un poco más grande
                    color = textColorSecondary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GesturesScreenPreview() {
    MusVistoTheme {
        GesturesScreen(rememberNavController())
    }
}