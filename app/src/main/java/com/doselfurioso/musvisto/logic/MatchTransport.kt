package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameState

/**
 * Canal de comunicación host↔clientes del multijugador (seam de red).
 *
 * Modela las cuatro operaciones que mapean **1:1 a Realtime Database** (ver el
 * esquema en docs/context/MULTIPLAYER_PLAN.md), de modo que el bucle del host y
 * el cliente se puedan construir y testear SIN red:
 *  - **host:** [publishView] = `setValue` en `views/{seat}`; [observeCommands] =
 *    listener en los nodos `actions/{seat}`.
 *  - **cliente:** [sendCommand] = `setValue` en `actions/{seat}`; [observeView] =
 *    listener en `views/{seat}`.
 *
 * Una implementación Firebase serializa con `GameCommandCodec` / kotlinx en la
 * frontera; [com.doselfurioso.musvisto.logic.MatchHostService] orquesta el lado
 * host. El transporte trabaja en objetos de dominio: la (de)serialización es
 * detalle de cada implementación.
 */
interface MatchTransport {
    // ---- Lado HOST ----
    /** Publica la vista (ya redactada) de un asiento para que su cliente la lea. */
    fun publishView(seatId: String, view: GameState)

    /** Registra el handler que recibe los comandos que envían los clientes. */
    fun observeCommands(onCommand: (seatId: String, command: GameCommand) -> Unit)

    // ---- Lado CLIENTE ----
    /** El cliente de [seatId] envía su comando al host. */
    fun sendCommand(seatId: String, command: GameCommand)

    /** El cliente de [seatId] observa los cambios de su vista. */
    fun observeView(seatId: String, onView: (GameState) -> Unit)
}
