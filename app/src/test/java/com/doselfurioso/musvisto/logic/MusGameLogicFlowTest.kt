package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Tests de CARACTERIZACIÓN del flujo de ronda de [MusGameLogic].
 *
 * NO afirman el comportamiento "correcto" según las reglas: congelan el
 * comportamiento ACTUAL del motor (transiciones de fase, reasignación de
 * turno, availableActions, roundHistory, isPuntoPhase, score y scoreBreakdown)
 * para que cualquier refactor estructural posterior sea byte-idéntico.
 *
 * Manos fijas y sin RNG en los caminos ejercitados (processAction /
 * resolveDeclaration / scoreRound son deterministas; no se llama a
 * shuffleDeck ni dealCards).
 *
 * Orden de asientos [p1,p2,p3,p4]; equipos p1/p3=teamA, p2/p4=teamB.
 * Con mano=p1 el orden de turno es p1 → p4 → p3 → p2.
 */
class MusGameLogicFlowTest {

    private lateinit var logic: MusGameLogic

    @Before
    fun setUp() {
        logic = MusGameLogic(Random(0))
    }

    private fun player(id: String, team: String, hand: List<Card>) =
        Player(id = id, name = id, team = team, avatarResId = 0, hand = hand)

    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    /** Estado en MUS, mano p1, listo para NoMus. */
    private fun musState(
        h1: List<Card>, h2: List<Card>, h3: List<Card>, h4: List<Card>
    ) = GameState(
        players = listOf(
            player("p1", "teamA", h1),
            player("p2", "teamB", h2),
            player("p3", "teamA", h3),
            player("p4", "teamB", h4)
        ),
        deck = emptyList(),
        manoPlayerId = "p1",
        currentTurnPlayerId = "p1",
        gamePhase = GamePhase.MUS,
        availableActions = listOf(GameAction.Mus, GameAction.NoMus)
    )

    private fun act(s: GameState, a: GameAction, pid: String) = logic.processAction(s, a, pid)

    /** Da Paso por cada jugador en turno hasta que cambie de fase. */
    private fun pasoLance(start: GameState): GameState {
        var s = start
        val phase = s.gamePhase
        var guard = 0
        while (s.gamePhase == phase && guard++ < 8) {
            s = act(s, GameAction.Paso, s.currentTurnPlayerId!!)
        }
        return s
    }

    // Manos sin pares y juego < 31 (4 cartas distintas en rango de emparejado).
    private fun lowHand(suit: Suit) = listOf(
        c(suit, Rank.AS), c(suit, Rank.CUATRO), c(suit, Rank.CINCO), c(suit, Rank.SEIS)
    )

    // ---------------------------------------------------------------------

    @Test
    fun `ronda completa a paso - transiciones, Punto y scoreBreakdown`() {
        var s = musState(
            lowHand(Suit.OROS), lowHand(Suit.COPAS),
            lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )

        // MUS: un NoMus del mano abre GRANDE.
        s = act(s, GameAction.NoMus, "p1")
        assertEquals(GamePhase.GRANDE, s.gamePhase)
        assertEquals("p1", s.currentTurnPlayerId)

        // GRANDE a paso -> CHICA.
        s = pasoLance(s)
        assertEquals(GamePhase.CHICA, s.gamePhase)
        assertEquals("p1", s.currentTurnPlayerId)
        assertEquals(listOf(GamePhase.GRANDE to "Paso"), s.roundHistory.map { it.lance to it.outcome })

        // CHICA a paso -> PARES_CHECK (sin acciones; lo resuelve resolveDeclaration).
        s = pasoLance(s)
        assertEquals(GamePhase.PARES_CHECK, s.gamePhase)
        assertEquals(emptyList<GameAction>(), s.availableActions)

        // Nadie tiene pares -> Skipped, avanza a JUEGO_CHECK.
        s = logic.resolveDeclaration(s)
        assertEquals(GamePhase.JUEGO_CHECK, s.gamePhase)
        assertFalse(s.isPuntoPhase)
        assertEquals(
            listOf(GamePhase.GRANDE to "Paso", GamePhase.CHICA to "Paso", GamePhase.PARES to "Skipped"),
            s.roundHistory.map { it.lance to it.outcome }
        )

        // Nadie llega a 31 -> ronda de Punto (sigue fase JUEGO con isPuntoPhase).
        s = logic.resolveDeclaration(s)
        assertEquals(GamePhase.JUEGO, s.gamePhase)
        assertTrue(s.isPuntoPhase)
        assertEquals("p1", s.currentTurnPlayerId)
        assertEquals(setOf("p1", "p2", "p3", "p4"), s.playersInLance)

        // Punto a paso -> ROUND_OVER.
        s = pasoLance(s)
        assertEquals(GamePhase.ROUND_OVER, s.gamePhase)
        assertEquals(
            listOf(
                GamePhase.GRANDE to "Paso", GamePhase.CHICA to "Paso",
                GamePhase.PARES to "Skipped", GamePhase.JUEGO to "Paso"
            ),
            s.roundHistory.map { it.lance to it.outcome }
        )

        // scoreRound: desglose con manos idénticas (gana p1/teamA por desempate de mano).
        val scored = logic.scoreRound(s)
        val a = scored.scoreBreakdown!!.teamAScoreDetails.map { it.reason to it.points }
        val b = scored.scoreBreakdown!!.teamBScoreDetails.map { it.reason to it.points }
        assertEquals(listOf("GRANDE" to 1, "CHICA" to 1, "Punto" to 1), a)
        assertEquals(emptyList<Pair<String, Int>>(), b)
    }

    @Test
    fun `GRANDE envido querido - LanceResult Querido y avance`() {
        // p1 tiene Rey (gana Grande); resto bajo.
        var s = musState(
            listOf(c(Suit.OROS, Rank.REY), c(Suit.OROS, Rank.CUATRO), c(Suit.OROS, Rank.CINCO), c(Suit.OROS, Rank.SEIS)),
            lowHand(Suit.COPAS), lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")
        assertEquals(GamePhase.GRANDE, s.gamePhase)

        // p1 envida 2; primer oponente en responder es p4.
        s = act(s, GameAction.Envido(2), "p1")
        assertEquals("p4", s.currentTurnPlayerId)
        assertEquals(2, s.currentBet?.amount)
        assertEquals(listOf("p4", "p2"), s.playersPendingResponse)

        // p4 quiere -> lance Querido, avanza a CHICA, turno a la mano.
        s = act(s, GameAction.Quiero, "p4")
        assertEquals(GamePhase.CHICA, s.gamePhase)
        assertEquals("p1", s.currentTurnPlayerId)
        assertNull(s.currentBet)
        assertEquals(2, s.agreedBets[GamePhase.GRANDE])
        assertEquals(
            listOf(GamePhase.GRANDE to "Querido"),
            s.roundHistory.map { it.lance to it.outcome }
        )
        assertEquals(2, s.roundHistory.first().amount)
    }

    @Test
    fun `GRANDE envido no querido - puntos inmediatos al equipo apostante`() {
        var s = musState(
            lowHand(Suit.OROS), lowHand(Suit.COPAS),
            lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")

        // p1 (teamA) envida 2; responden p4 y luego p2 (teamB).
        s = act(s, GameAction.Envido(2), "p1")
        s = act(s, GameAction.NoQuiero, "p4")
        // Aún queda p2 por responder: no se resuelve todavía.
        assertEquals("p2", s.currentTurnPlayerId)
        assertEquals(GamePhase.GRANDE, s.gamePhase)

        s = act(s, GameAction.NoQuiero, "p2")
        // Rechazo definitivo: teamA gana pointsIfRejected (1) al instante.
        assertEquals(1, s.score["teamA"])
        assertEquals(0, s.score["teamB"])
        assertEquals(GamePhase.CHICA, s.gamePhase)
        assertEquals(
            listOf(GamePhase.GRANDE to "No Querido"),
            s.roundHistory.map { it.lance to it.outcome }
        )
        assertEquals(1, s.scoreEvents.size)
        assertEquals("teamA", s.scoreEvents.first().teamId)
    }

    @Test
    fun `ordago querido en GRANDE - fin de partida`() {
        var s = musState(
            listOf(c(Suit.OROS, Rank.REY), c(Suit.OROS, Rank.CUATRO), c(Suit.OROS, Rank.CINCO), c(Suit.OROS, Rank.SEIS)),
            lowHand(Suit.COPAS), lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")

        s = act(s, GameAction.Órdago, "p1")
        assertEquals(40, s.currentBet?.amount)
        assertTrue(s.currentBet?.isOrdago == true)
        assertEquals("p4", s.currentTurnPlayerId)

        s = act(s, GameAction.Quiero, "p4")
        // p1 (teamA) gana Grande con el Rey -> 40 puntos, GAME_OVER.
        assertEquals(GamePhase.GAME_OVER, s.gamePhase)
        assertEquals("teamA", s.winningTeam)
        assertEquals(40, s.score["teamA"])
        assertEquals(GamePhase.GRANDE, s.ordagoInfo?.lance)
        assertEquals("p1", s.ordagoInfo?.winnerId)
    }

    @Test
    fun `ordago no querido en GRANDE - punto minimo y sigue la ronda`() {
        var s = musState(
            lowHand(Suit.OROS), lowHand(Suit.COPAS),
            lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")

        s = act(s, GameAction.Órdago, "p1")
        s = act(s, GameAction.NoQuiero, "p4")
        s = act(s, GameAction.NoQuiero, "p2")

        // teamA gana 1 (pointsIfRejected) y la ronda continúa en CHICA.
        assertEquals(1, s.score["teamA"])
        assertEquals(GamePhase.CHICA, s.gamePhase)
        assertNotEquals(GamePhase.GAME_OVER, s.gamePhase)
        assertEquals(
            listOf(GamePhase.GRANDE to "No Querido"),
            s.roundHistory.map { it.lance to it.outcome }
        )
    }

    @Test
    fun `PARES_CHECK ambos equipos con pares - pasa a apuestas`() {
        // p1 y p2 con pares; p3/p4 sin pares -> ambos equipos juegan.
        var s = musState(
            listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY), c(Suit.OROS, Rank.CINCO), c(Suit.OROS, Rank.CUATRO)),
            listOf(c(Suit.COPAS, Rank.SOTA), c(Suit.ESPADAS, Rank.SOTA), c(Suit.COPAS, Rank.CUATRO), c(Suit.COPAS, Rank.SEIS)),
            lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")
        s = pasoLance(s) // GRANDE -> CHICA
        s = pasoLance(s) // CHICA -> PARES_CHECK
        assertEquals(GamePhase.PARES_CHECK, s.gamePhase)

        s = logic.resolveDeclaration(s)
        assertEquals(GamePhase.PARES, s.gamePhase)
        assertFalse(s.isPuntoPhase)
        assertEquals(setOf("p1", "p2"), s.playersInLance)
        // Primer en orden de turno (p1,p4,p3,p2) con pares = p1.
        assertEquals("p1", s.currentTurnPlayerId)
        assertEquals(
            listOf(GamePhase.GRANDE to "Paso", GamePhase.CHICA to "Paso"),
            s.roundHistory.map { it.lance to it.outcome }
        )
    }

    @Test
    fun `PARES_CHECK un solo equipo con pares - Skipped y avanza`() {
        // Solo p1 (teamA) con pares.
        var s = musState(
            listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY), c(Suit.OROS, Rank.CINCO), c(Suit.OROS, Rank.CUATRO)),
            lowHand(Suit.COPAS), lowHand(Suit.ESPADAS), lowHand(Suit.BASTOS)
        )
        s = act(s, GameAction.NoMus, "p1")
        s = pasoLance(s) // GRANDE -> CHICA
        s = pasoLance(s) // CHICA -> PARES_CHECK

        s = logic.resolveDeclaration(s)
        // Un solo equipo con jugada -> Skipped, avanza a JUEGO_CHECK.
        assertEquals(GamePhase.JUEGO_CHECK, s.gamePhase)
        assertEquals("p1", s.currentTurnPlayerId)
        assertEquals(
            listOf(
                GamePhase.GRANDE to "Paso", GamePhase.CHICA to "Paso",
                GamePhase.PARES to "Skipped"
            ),
            s.roundHistory.map { it.lance to it.outcome }
        )
    }

    @Test
    fun `pares no querido - la jugada va al que envido, no al de mejores pares`() {
        // p1 (teamA) par de Caballos; p2 (teamB) par de Reyes (gana el showdown).
        // El lance se cerró por NO QUERIDO ganado por teamA (el que envidó).
        val s = GameState(
            players = listOf(
                player("p1", "teamA", listOf(
                    c(Suit.OROS, Rank.CABALLO), c(Suit.COPAS, Rank.CABALLO),
                    c(Suit.OROS, Rank.AS), c(Suit.OROS, Rank.CUATRO)
                )),
                player("p2", "teamB", listOf(
                    c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY),
                    c(Suit.COPAS, Rank.AS), c(Suit.COPAS, Rank.CINCO)
                )),
                player("p3", "teamA", lowHand(Suit.BASTOS)),
                player("p4", "teamB", lowHand(Suit.ESPADAS))
            ),
            manoPlayerId = "p1",
            roundHistory = listOf(
                LanceResult(GamePhase.GRANDE, "Skipped"),
                LanceResult(GamePhase.CHICA, "Skipped"),
                LanceResult(GamePhase.PARES, "No Querido", amount = 1, winningTeam = "teamA"),
                LanceResult(GamePhase.JUEGO, "Skipped")
            ),
            scoreEvents = listOf(ScoreEventInfo("teamA", ScoreDetail("PARES No Querida", 1)))
        )

        // Sanidad: el showdown de pares lo ganaría teamB (Reyes > Caballos).
        assertEquals("teamB", logic.getParesWinner(s)?.team)

        val scored = logic.scoreRound(s)
        val aReasons = scored.scoreBreakdown!!.teamAScoreDetails.map { it.reason }
        val bReasons = scored.scoreBreakdown!!.teamBScoreDetails.map { it.reason }

        // La jugada de pares la anota teamA (ganó el lance por la no querida).
        assertTrue("teamA debe anotar su jugada de pares", aReasons.any { it.startsWith("Pares (p1)") })
        // teamB (mejores pares pero plegó) NO anota jugada de pares.
        assertFalse(
            "teamB no debe anotar jugada de pares",
            bReasons.any { it.startsWith("Pares") || it.startsWith("Medias") || it.startsWith("Duples") }
        )
    }
}
