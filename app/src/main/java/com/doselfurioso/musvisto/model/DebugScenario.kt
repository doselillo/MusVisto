package com.doselfurioso.musvisto.model

/**
 * Escenario de prueba para forzar una situación concreta de partida desde el
 * panel de debug (manos exactas, quién es mano, en qué lance arrancar).
 *
 * Es un modelo puro; las instancias concretas viven en `src/debug/` y solo se
 * usan en builds debug. En release el selector es un no-op y nadie las crea.
 *
 * @param name etiqueta visible en el panel de debug.
 * @param hands mano forzada por id de jugador ("p1" humano, "p2"/"p3"/"p4" IA).
 *              Cada lista debe tener 4 cartas y no repetir cartas entre manos.
 * @param manoId jugador que es "mano" en la ronda.
 * @param startAtMus si es `true`, la partida arranca en MUS (para probar
 *              corte de Mus / descarte). Si es `false` (por defecto), se emite
 *              un "No hay mus" del mano y la partida aterriza en GRANDE con las
 *              manos exactas intactas (sin descarte que las altere).
 */
data class DebugScenario(
    val name: String,
    val hands: Map<String, List<Card>>,
    val manoId: String,
    val startAtMus: Boolean = false
)
