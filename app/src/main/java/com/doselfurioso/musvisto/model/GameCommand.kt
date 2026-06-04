package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Representación de RED de una acción de juego (Fase 0.1 del plan multijugador,
 * docs/context/MULTIPLAYER_PLAN.md).
 *
 * `GameAction` es la acción de UI: carga texto, tipo de botón e iconos
 * (`R.drawable`), todo atado a recursos Android que NO tienen sentido viajando
 * por la red ni en un servidor. `GameCommand` es la cara mínima y serializable
 * que SÍ viaja: solo la información que el motor (`MusGameLogic.processAction`)
 * necesita para reproducir la acción de forma determinista en otro dispositivo.
 *
 * Se modelan las acciones que pasan por el reducer MÁS las señas (`ShowGesture`,
 * Fase 4.3): la seña NO pasa por el reducer —el host la intercepta, como `Continue`,
 * y computa la seña VERAZ de la mano del emisor— pero SÍ viaja por la red. Las acciones
 * puramente locales de UI (abrir/cerrar el selector de envite, pausa, log de debug) NO
 * viajan, así que no tienen comando.
 *
 * Esta pieza es ADDITIVA: define el formato de red sin rewirear todavía el motor
 * (el host consumirá `GameCommand` en una sub-fase posterior).
 */
@Serializable
sealed class GameCommand {
    @Serializable object Mus : GameCommand()
    @Serializable object NoMus : GameCommand()
    /** El descarte es autónomo: lleva las cartas (hoy viven en el estado, no en
     *  la acción) para reproducirse en el host sin estado compartido. */
    @Serializable data class Discard(val cards: List<Card>) : GameCommand()
    @Serializable object Continue : GameCommand()
    @Serializable object NewGame : GameCommand()
    @Serializable object Tengo : GameCommand()
    @Serializable object NoTengo : GameCommand()
    @Serializable object Pass : GameCommand()                       // Paso
    @Serializable data class Bet(val amount: Int) : GameCommand()   // Envido
    @Serializable object Accept : GameCommand()                     // Quiero
    @Serializable object Decline : GameCommand()                    // NoQuiero
    @Serializable object Ordago : GameCommand()
    /** Seña del jugador (Fase 4.3). NO pasa por el reducer: el host la intercepta (como
     *  [Continue]) y computa la seña VERAZ de la mano del emisor. Sin payload: el kind lo
     *  deriva el host de la mano → no se puede mentir. */
    @Serializable object ShowGesture : GameCommand()
}

/**
 * Acción de UI → comando de red. Devuelve `null` para las acciones que NO pasan
 * por el reducer (selector de envite, pausa, señas, log): son locales, no viajan.
 *
 * [selectedCards] son las cartas del descarte. Hoy viven en
 * `GameState.selectedCardsForDiscard` (no en la acción), así que se pasan aquí
 * para que el comando de descarte sea autónomo.
 */
fun GameAction.toCommand(selectedCards: Set<Card> = emptySet()): GameCommand? = when (this) {
    is GameAction.Mus -> GameCommand.Mus
    is GameAction.NoMus -> GameCommand.NoMus
    is GameAction.ConfirmDiscard -> GameCommand.Discard(selectedCards.toList())
    is GameAction.Continue -> GameCommand.Continue
    is GameAction.NewGame -> GameCommand.NewGame
    is GameAction.Tengo -> GameCommand.Tengo
    is GameAction.NoTengo -> GameCommand.NoTengo
    is GameAction.Paso -> GameCommand.Pass
    is GameAction.Envido -> GameCommand.Bet(amount)
    is GameAction.Quiero -> GameCommand.Accept
    is GameAction.NoQuiero -> GameCommand.Decline
    is GameAction.Órdago -> GameCommand.Ordago
    // Seña (Fase 4.3): viaja, pero el host la intercepta antes del reducer (no es acción de juego).
    is GameAction.ShowGesture -> GameCommand.ShowGesture
    // Locales / no pasan por el reducer: no viajan.
    is GameAction.ToggleBetSelector,
    is GameAction.CancelBetSelection,
    is GameAction.TogglePauseMenu,
    is GameAction.LogAction -> null
}

/**
 * Comando de red → acción de UI, para reinyectar una acción remota en el pipeline
 * existente (`gameLogic.processAction` acepta `GameAction`). El descarte mapea a
 * `ConfirmDiscard`; sus cartas ([GameCommand.Discard.cards]) se aplican aparte al
 * estado (`GameState.selectedCardsForDiscard`), igual que hoy el flujo de la IA.
 */
fun GameCommand.toAction(): GameAction = when (this) {
    is GameCommand.Mus -> GameAction.Mus
    is GameCommand.NoMus -> GameAction.NoMus
    is GameCommand.Discard -> GameAction.ConfirmDiscard
    is GameCommand.Continue -> GameAction.Continue
    is GameCommand.NewGame -> GameAction.NewGame
    is GameCommand.Tengo -> GameAction.Tengo
    is GameCommand.NoTengo -> GameAction.NoTengo
    is GameCommand.Pass -> GameAction.Paso
    is GameCommand.Bet -> GameAction.Envido(amount)
    is GameCommand.Accept -> GameAction.Quiero
    is GameCommand.Decline -> GameAction.NoQuiero
    is GameCommand.Ordago -> GameAction.Órdago
    // El host intercepta ShowGesture ANTES del reducer (Fase 4.3); esta rama existe por
    // exhaustividad del `when` y no debería ejecutarse en el flujo normal.
    is GameCommand.ShowGesture -> GameAction.ShowGesture
}

/**
 * Serialización del comando para red/persistencia. JSON estable e independiente
 * de Android (sin `R.drawable`). kotlinx.serialization maneja la jerarquía
 * sellada de forma polimórfica (campo discriminador "type").
 */
object GameCommandCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(command: GameCommand): String = json.encodeToString(command)

    fun decode(text: String): GameCommand = json.decodeFromString(text)
}
