package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.ActiveGestureInfo
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.GameAction
import com.doselfurioso.musvisto.model.GameCommand
import com.doselfurioso.musvisto.model.GameCommandCodec
import com.doselfurioso.musvisto.model.GamePhase
import com.doselfurioso.musvisto.model.GameState
import com.doselfurioso.musvisto.model.GestureKind
import com.doselfurioso.musvisto.model.Player
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Prueba de integración del núcleo host-autoritativo (prep multijugador): los tres
 * primitivos de Fase 0 (GameCommand + reducer + StateRedactor) componen en [MatchHost].
 */
class MatchHostTest {

    private fun players() = listOf(
        Player(id = "p1", name = "A1", avatarResId = 0, team = "teamA"),
        Player(id = "p2", name = "B1", avatarResId = 0, isAi = true, team = "teamB"),
        Player(id = "p3", name = "A2", avatarResId = 0, isAi = true, team = "teamA"),
        Player(id = "p4", name = "B2", avatarResId = 0, isAi = true, team = "teamB")
    )

    /** Estado de una mano recién repartida en fase MUS, determinista (semilla fija). */
    private fun dealtMusState(): GameState {
        val logic = MusGameLogic(Random(0))
        val deck = logic.shuffleDeck(logic.createDeck())
        val (dealt, rest) = logic.dealCards(players(), deck, "p1")
        return GameState(
            players = dealt,
            deck = rest,
            gamePhase = GamePhase.MUS,
            currentTurnPlayerId = "p1",
            manoPlayerId = "p1",
            playersInLance = players().map { it.id }.toSet(),
            // El host computa availableActions por asiento al entrar en MUS; el
            // reducer las exige (processAction valida la acción contra ellas) y son
            // @Transient → no viajan por la red, las recalcula el host.
            availableActions = listOf(GameAction.Mus, GameAction.NoMus)
        )
    }

    @Test
    fun `viewFor redacta la mano ajena pero el host conserva el estado completo`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())

        val p1View = host.viewFor("p1")
        assertEquals(4, p1View.players.first { it.id == "p1" }.hand.size)
        assertTrue(p1View.players.first { it.id == "p2" }.hand.isEmpty())
        assertTrue("La vista no expone el mazo", p1View.deck.isEmpty())

        // El host sigue teniendo TODAS las manos (es la autoridad).
        assertTrue(host.authoritativeState.players.all { it.hand.size == 4 })
    }

    @Test
    fun `viewFor entrega los comandos legales solo al asiento de turno`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())

        // En MUS el turno es de p1 con [Mus, NoMus] → su vista los lleva como GameCommand
        // (espejo serializable de availableActions, que es @Transient y no viaja).
        assertEquals(
            listOf(GameCommand.Mus, GameCommand.NoMus),
            host.viewFor("p1").availableCommands
        )
        // Un asiento que NO es de turno no recibe comandos (no puede actuar).
        assertTrue(host.viewFor("p2").availableCommands.isEmpty())
    }

    @Test
    fun `submitCommand aplica el comando al estado autoritativo`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        val before = host.authoritativeState

        host.submitCommand("p1", GameCommand.Mus)

        assertNotEquals("Pedir Mus debe avanzar el estado", before, host.authoritativeState)
    }

    @Test
    fun `submitCommand sella la ultima accion para anunciarla en el cliente`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        host.submitCommand("p1", GameCommand.Mus)

        // La vista de OTRO asiento lleva quién hizo qué (info pública), en forma
        // serializable (lastAction es @Transient y no viaja por la red).
        val view = host.viewFor("p2")
        assertEquals("p1", view.lastActionView?.seatId)
        assertEquals(GameCommand.Mus, view.lastActionView?.command)
    }

    @Test
    fun `un comando que viaja por el codec produce el mismo estado que el directo`() {
        val initial = dealtMusState()
        val direct = MatchHost(MusGameLogic(Random(0)), initial)
        val overWire = MatchHost(MusGameLogic(Random(0)), initial)

        direct.submitCommand("p1", GameCommand.Mus)
        // Simula el viaje por red: encode -> JSON -> decode.
        val decoded = GameCommandCodec.decode(GameCommandCodec.encode(GameCommand.Mus))
        overWire.submitCommand("p1", decoded)

        // Igualdad LÓGICA: el reducer sella cada acción con LastActionInfo.seq =
        // nanoTime (único por construcción, @Transient → no viaja por la red), así
        // que se normaliza antes de comparar. El resto del estado es determinista.
        assertEquals(stable(direct.authoritativeState), stable(overWire.authoritativeState))
    }

    /** Quita los sellos de anuncio (seq = nanoTime, no deterministas ni de red). */
    private fun stable(s: GameState) = s.copy(
        lastAction = null, actionLog = emptyList(), currentLanceActions = emptyMap()
    )

    @Test
    fun `el descarte autonomo inyecta sus cartas y el host lo aplica`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        // Los 4 piden Mus en orden de turno → fase DISCARD (mano al descarte).
        repeat(4) { host.submitCommand(host.authoritativeState.currentTurnPlayerId!!, GameCommand.Mus) }
        assertEquals(GamePhase.DISCARD, host.authoritativeState.gamePhase)

        // El comando de descarte LLEVA sus cartas (autónomo): el host las inyecta en
        // selectedCardsForDiscard antes de reducir; sin eso el reducer lo ignora.
        val mano = host.authoritativeState.currentTurnPlayerId!!
        val toDiscard = host.authoritativeState.players.first { it.id == mano }.hand.take(2)
        host.submitCommand(mano, GameCommand.Discard(toDiscard))

        assertEquals(2, host.authoritativeState.discardCounts[mano])
    }

    // --- Señas online (Fase 4.2) ---

    @Test
    fun `showGesture muestra la sena al companero y nunca filtra knownGestures`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        host.showGesture("p3", GestureKind.REYES_2) // p3 es teamA

        val p1View = host.viewFor("p1") // p1 teamA = compañero de p3
        assertEquals(ActiveGestureInfo("p3", GestureKind.REYES_2), p1View.activeGesture)
        assertTrue("knownGestures es interno del host (anti-trampa)", p1View.knownGestures.isEmpty())
        // El host SÍ conserva la memoria completa: la usa AILogic para apoyar/interceptar.
        assertTrue(host.authoritativeState.knownGestures.containsKey("p3"))
    }

    @Test
    fun `viewFor redacta la sena RIVAL segun el predicado de caza`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        val kind = GestureKind.JUEGO_31
        host.showGesture("p3", kind) // p3 teamA señaliza

        val p2View = host.viewFor("p2") // p2 teamB = rival
        val caza = GestureVisibility.perceivesOpponentSign(
            manoPlayerId = host.authoritativeState.manoPlayerId,
            gesturerId = "p3",
            observerId = "p2",
            kind = kind,
            prob = GestureVisibility.HUMAN_INTERCEPT_PROB
        )
        if (caza) {
            assertEquals(ActiveGestureInfo("p3", kind), p2View.activeGesture)
        } else {
            assertNull("Seña rival no cazada se borra de la vista", p2View.activeGesture)
        }
    }

    @Test
    fun `planGestures respeta el Mus corrido (sin senas hasta el primer corte)`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState().copy(musCorrido = true))
        assertTrue(host.planGestures().isEmpty())
        assertTrue(host.authoritativeState.pendingGestures.isEmpty())
    }

    @Test
    fun `planGestures pre-decide senas VERACES para las IA con pareja`() {
        val state = dealtMusState()
        // rng que SIEMPRE pasa la tirada (nextFloat=0): cada IA con mano señalizable señaliza.
        val alwaysSignal = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        val host = MatchHost(MusGameLogic(Random(0)), state, alwaysSignal)
        val pending = host.planGestures()

        assertTrue("hay al menos una IA con mano señalizable", pending.isNotEmpty())
        assertTrue("p1 (humano) no pre-decide seña", "p1" !in pending)
        // Cada seña es VERAZ: coincide con determineGesture de esa mano (no se puede mentir).
        val logic = MusGameLogic(Random(0))
        pending.forEach { (id, kind) ->
            val hand = state.players.first { it.id == id }.hand
            assertEquals("seña veraz de $id", logic.determineGesture(hand), kind)
        }
        assertEquals("planGestures guarda pendingGestures en el host", pending, host.authoritativeState.pendingGestures)
    }

    // --- Seña del HUMANO online (Fase 4.3) ---

    /** Solo importan los rangos para `determineGesture`; suits cíclicos (sin cartas duplicadas). */
    private fun seat(id: String, team: String, vararg ranks: Rank) = Player(
        id = id, name = id, avatarResId = 0, team = team,
        hand = ranks.mapIndexed { i, r -> Card(Suit.values()[i % Suit.values().size], r) }
    )

    @Test
    fun `showHumanGesture muestra la sena VERAZ del emisor al companero y la redacta al rival`() {
        // p1 (teamA, humano) con DOS REYES → REYES_2.
        val table = listOf(
            seat("p1", "teamA", Rank.REY, Rank.REY, Rank.AS, Rank.CUATRO),
            seat("p2", "teamB", Rank.AS, Rank.AS, Rank.DOS, Rank.TRES),
            seat("p3", "teamA", Rank.SOTA, Rank.CABALLO, Rank.CINCO, Rank.SEIS),
            seat("p4", "teamB", Rank.SOTA, Rank.SOTA, Rank.CINCO, Rank.SEIS)
        )
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState().copy(players = table, manoPlayerId = "p1"))

        assertEquals(GestureKind.REYES_2, host.showHumanGesture("p1"))
        // El compañero p3 (teamA) la ve SIEMPRE.
        assertEquals(ActiveGestureInfo("p1", GestureKind.REYES_2), host.viewFor("p3").activeGesture)
        // El rival p2 (teamB) solo si la caza (mismo predicado/seed que la IA).
        val caza = GestureVisibility.perceivesOpponentSign(
            manoPlayerId = "p1", gesturerId = "p1", observerId = "p2",
            kind = GestureKind.REYES_2, prob = GestureVisibility.HUMAN_INTERCEPT_PROB
        )
        assertEquals(
            if (caza) ActiveGestureInfo("p1", GestureKind.REYES_2) else null,
            host.viewFor("p2").activeGesture
        )
    }

    @Test
    fun `showHumanGesture no hace nada en Mus corrido ni con mano no senalizable`() {
        // Mus corrido (#17): sin señas.
        val musCorrido = MatchHost(MusGameLogic(Random(0)), dealtMusState().copy(musCorrido = true))
        assertNull(musCorrido.showHumanGesture("p1"))
        assertNull(musCorrido.authoritativeState.activeGesture)
    }

    @Test
    fun `tras aplicar un comando, la vista del rival sigue redactada`() {
        val host = MatchHost(MusGameLogic(Random(0)), dealtMusState())
        host.submitCommand("p1", GameCommand.Mus)

        val p2View = host.viewFor("p2")
        assertEquals(4, p2View.players.first { it.id == "p2" }.hand.size)
        listOf("p1", "p3", "p4").forEach {
            assertTrue("La mano de $it debe seguir oculta a p2", p2View.players.first { p -> p.id == it }.hand.isEmpty())
        }
    }
}
