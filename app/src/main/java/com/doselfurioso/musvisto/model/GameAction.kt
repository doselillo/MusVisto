package com.doselfurioso.musvisto.model

import androidx.compose.ui.graphics.vector.ImageVector
import com.doselfurioso.musvisto.R // Importa tus recursos

// Un enum para definir el tipo de color del botón
enum class ButtonColorType {
    PASS, BET, CONFIRM, DENY, ULTIMATE
}

sealed class GameAction(
    val displayText: String,
    val colorType: ButtonColorType,
    val iconResId: Int?
) {
    // Mus Phase Actions
    object Mus : GameAction("Mus", ButtonColorType.CONFIRM, R.drawable.ic_cycle)
    object NoMus : GameAction("No Mus", ButtonColorType.DENY, R.drawable.ic_stop)
    object ConfirmDiscard : GameAction("Descartar", ButtonColorType.CONFIRM, R.drawable.ic_check)

    // Betting Actions
    object Paso : GameAction("Paso", ButtonColorType.PASS, R.drawable.ic_pass)
    data class Envido(val amount: Int) : GameAction("Envido $amount", ButtonColorType.BET, R.drawable.ic_bet)
    object Quiero : GameAction("Quiero", ButtonColorType.CONFIRM, R.drawable.ic_check)
    object NoQuiero : GameAction("No Quiero", ButtonColorType.DENY, R.drawable.ic_close)
    object Órdago : GameAction("Órdago", ButtonColorType.ULTIMATE, R.drawable.ic_ordago)
}