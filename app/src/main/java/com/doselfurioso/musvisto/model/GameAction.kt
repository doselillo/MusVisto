package com.doselfurioso.musvisto.model

import androidx.compose.ui.graphics.vector.ImageVector
import com.doselfurioso.musvisto.R // Importa tus recursos

// Un enum para definir el tipo de color del botón
enum class ButtonColorType {
    NEUTRAL, CONFIRM, DENY
}

sealed class GameAction(
    val displayText: String,
    val colorType: ButtonColorType,
    val iconResId: Int? // El ID del recurso del icono
) {
    ///TODO descomentar
   // object Mus : GameAction("Mus", ButtonColorType.CONFIRM, R.drawable.ic_cycle)
   // object NoMus : GameAction("No Mus", ButtonColorType.DENY, R.drawable.ic_stop)
   // object Envido : GameAction("Envido", ButtonColorType.NEUTRAL, R.drawable.ic_arrow_up)
   // object Quiero : GameAction("Quiero", ButtonColorType.CONFIRM, R.drawable.ic_check)
   // object NoQuiero : GameAction("No Quiero", ButtonColorType.DENY, R.drawable.ic_close)
    // Añadiremos más acciones (Órdago, Paso, etc.) aquí más tarde
}