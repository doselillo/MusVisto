package com.doselfurioso.musvisto.model

import com.doselfurioso.musvisto.R // Importa tus recursos

// Un enum para definir el tipo de color del botón
enum class ActionType {
    PASS, BET, CONFIRM, DENY, ULTIMATE
}

sealed class GameAction(
    val displayText: String,
    val colorType: ActionType,
    val iconResId: Int?
) {
    // Mus Phase Actions
    object Mus : GameAction("Mus", ActionType.CONFIRM, R.drawable.ic_cycle)
    object NoMus : GameAction("No Mus", ActionType.DENY, R.drawable.ic_stop)
    object ConfirmDiscard : GameAction("Descartar", ActionType.CONFIRM, R.drawable.ic_check)
    object Continue : GameAction("Continuar", ActionType.CONFIRM, R.drawable.ic_continue)
    object NewGame : GameAction("Jugar de Nuevo", ActionType.CONFIRM, R.drawable.ic_newgame)



    // Betting Actions
    object Paso : GameAction("Paso", ActionType.PASS, R.drawable.ic_pass)
    data class Envido(val amount: Int) : GameAction("Envido $amount", ActionType.BET, R.drawable.ic_bet)
    object Quiero : GameAction("Quiero", ActionType.CONFIRM, R.drawable.ic_check)
    object NoQuiero : GameAction("No Quiero", ActionType.DENY, R.drawable.ic_close)
    object Órdago : GameAction("Órdago", ActionType.ULTIMATE, R.drawable.ic_ordago)


    data class LogAction(val text: String) : GameAction(text, ActionType.PASS, null)


}