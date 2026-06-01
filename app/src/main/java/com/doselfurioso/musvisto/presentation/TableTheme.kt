package com.doselfurioso.musvisto.presentation

import androidx.compose.ui.graphics.Color

/**
 * Colores de tapete elegibles (#36). La clave (`GameSettings.tableColor`) se
 * persiste como String; el mapeo a [Color] vive aquí, en la capa de UI, para no
 * meter colores en el modelo. Lo consumen la pantalla de selección (swatches),
 * el tapete de la partida ([GameScreen]) y la pantalla de Opciones.
 */
object TableTheme {

    /**
     * Un tapete: [color] de fondo + [accent] para los botones que van ENCIMA del
     * tapete (mismo tono, más claro → "a juego con el tapete", #36; texto blanco
     * encima). El acento solo aplica donde el fondo es el tapete (mesa/Opciones);
     * las pantallas de marca (menú/selección) usan su propio verde.
     */
    data class Option(val key: String, val label: String, val color: Color, val accent: Color)

    val options: List<Option> = listOf(
        Option("GREEN", "Verde", Color(0xFF006A4E), Color(0xFF6A994E)),
        Option("GARNET", "Granate", Color(0xFF6A1B2A), Color(0xFFBB5A4D)),
        Option("BLUE", "Azul", Color(0xFF14385C), Color(0xFF3F76A8))
    )

    private val byKey = options.associateBy { it.key }

    private fun optionFor(key: String): Option = byKey[key] ?: options.first()

    /** Color del tapete (fondo); cae al verde clásico si la clave no existe. */
    fun colorFor(key: String): Color = optionFor(key).color

    /** Color de acento (botones) a juego con el tapete elegido. */
    fun accentFor(key: String): Color = optionFor(key).accent
}
