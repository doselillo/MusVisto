package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Snapshot de PARIDAD DETERMINISTA de [AILogic.makeDecision].
 *
 * Matriz de regresión de IA que vive en master, independiente del
 * simulador (que está en otra rama y mide calidad agregada, no paridad
 * caso-a-caso). Para cada caso del corpus se crea una `AILogic` con `rng`
 * de semilla FIJA y se serializa la decisión (acción + cartas a descartar;
 * NO el debugLog, que incluye un UUID aleatorio). Cada caso usa su propia
 * instancia con la misma semilla → independiente del orden y de añadir
 * casos nuevos.
 *
 * Si esto se rompe tras un refactor de AILogic, las decisiones cambiaron:
 * el refactor NO fue de comportamiento-cero. Para regenerar el golden a
 * propósito (cambio de IA intencional), copiar el contenido del archivo
 * `app/build/ai-snapshot-actual.txt` a [EXPECTED].
 */
class AILogicSnapshotTest {

    private val gameLogic = MusGameLogic(Random(0))
    private val SEED = 777L

    private fun c(s: Suit, r: Rank) = Card(s, r)

    // Arquetipos de mano.
    private val fourKings = listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY), c(Suit.ESPADAS, Rank.REY), c(Suit.BASTOS, Rank.REY))
    private val juego31 = listOf(c(Suit.OROS, Rank.SOTA), c(Suit.COPAS, Rank.SOTA), c(Suit.ESPADAS, Rank.SOTA), c(Suit.BASTOS, Rank.AS))
    private val duplesRC = listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY), c(Suit.ESPADAS, Rank.CINCO), c(Suit.BASTOS, Rank.CINCO))
    private val weak = listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.SOTA), c(Suit.ESPADAS, Rank.CUATRO), c(Suit.BASTOS, Rank.AS))
    private val lowChica = listOf(c(Suit.OROS, Rank.AS), c(Suit.COPAS, Rank.CUATRO), c(Suit.ESPADAS, Rank.CINCO), c(Suit.BASTOS, Rank.SEIS))
    private val medium = listOf(c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.CABALLO), c(Suit.ESPADAS, Rank.SIETE), c(Suit.BASTOS, Rank.CUATRO))
    // Pares únicos (cobertura de #13: bonus de mano escalado por rango).
    private val par7 = listOf(
        c(Suit.OROS, Rank.SIETE), c(Suit.COPAS, Rank.SIETE),
        c(Suit.ESPADAS, Rank.CUATRO), c(Suit.BASTOS, Rank.CINCO)
    )
    private val parAs = listOf(
        c(Suit.OROS, Rank.AS), c(Suit.COPAS, Rank.AS),
        c(Suit.ESPADAS, Rank.CUATRO), c(Suit.BASTOS, Rank.CINCO)
    )
    private val parRey = listOf(
        c(Suit.OROS, Rank.REY), c(Suit.COPAS, Rank.REY),
        c(Suit.ESPADAS, Rank.CUATRO), c(Suit.BASTOS, Rank.CINCO)
    )

    private val archetypes = listOf(
        "4REY" to fourKings, "J31" to juego31, "DUP" to duplesRC,
        "WEAK" to weak, "LOWC" to lowChica, "MED" to medium,
        "P7" to par7, "PAS" to parAs, "PREY" to parRey
    )

    /** ai = p2 (teamB). Compañero p4 (teamB). Rivales p1/p3 (teamA). */
    private fun state(
        aiHand: List<Card>,
        phase: GamePhase,
        manoId: String = "p2",
        myScore: Int = 0,
        oppScore: Int = 0,
        bet: BetInfo? = null
    ): Pair<GameState, Player> {
        val p1 = Player("p1", "p1", team = "teamA", avatarResId = 0, hand = medium)
        val p2 = Player("p2", "p2", team = "teamB", avatarResId = 0, isAi = true, hand = aiHand)
        val p3 = Player("p3", "p3", team = "teamA", avatarResId = 0, hand = medium)
        val p4 = Player("p4", "p4", team = "teamB", avatarResId = 0, isAi = true, hand = weak)
        val gs = GameState(
            players = listOf(p1, p2, p3, p4),
            gamePhase = phase,
            manoPlayerId = manoId,
            currentTurnPlayerId = "p2",
            currentBet = bet,
            score = mapOf("teamA" to oppScore, "teamB" to myScore)
        )
        return gs to p2
    }

    private fun serialize(a: GameAction): String = when (a) {
        is GameAction.Envido -> "Envido(${a.amount})"
        is GameAction.ConfirmDiscard -> "ConfirmDiscard"
        else -> a::class.simpleName ?: a.toString()
    }

    private fun decide(name: String, gs: GameState, ai: Player): String {
        val ai1 = AILogic(gameLogic, Random(SEED))
        val d = ai1.makeDecision(gs, ai)
        val cards = d.cardsToDiscard
            .map { it.suit.name.take(1) + it.rank.name.take(2) }
            .sorted()
            .joinToString(",")
        return "$name => ${serialize(d.action)}" + if (cards.isNotEmpty()) " [$cards]" else ""
    }

    @Test
    fun `ai decision snapshot - paridad determinista`() {
        val lines = mutableListOf<String>()

        // MUS y DISCARD por arquetipo, con mano propia y con mano del compañero.
        for ((tag, hand) in archetypes) {
            for (mano in listOf("p2", "p4")) {
                val (gs, ai) = state(hand, GamePhase.MUS, manoId = mano)
                lines += decide("MUS/$tag/mano=$mano", gs, ai)
            }
            val (gd, ad) = state(hand, GamePhase.DISCARD)
            lines += decide("DISCARD/$tag", gd, ad)
        }

        // Apertura de lance por arquetipo y fase, con marcador par / detrás / delante.
        for ((tag, hand) in archetypes) {
            for (phase in listOf(GamePhase.GRANDE, GamePhase.CHICA, GamePhase.PARES, GamePhase.JUEGO)) {
                for ((sTag, my, op) in listOf(
                    Triple("par", 15, 15), Triple("detras", 5, 30), Triple("delante", 30, 5)
                )) {
                    val (gs, ai) = state(hand, phase, myScore = my, oppScore = op)
                    lines += decide("OPEN/$phase/$tag/$sTag", gs, ai)
                }
            }
        }

        // Respuesta a envite normal y a órdago, mano vs postre, marcador variado.
        for ((tag, hand) in archetypes) {
            val normalBet = BetInfo(amount = 2, bettingPlayerId = "p1", respondingPlayerId = "p2")
            val ordago = BetInfo(amount = 40, bettingPlayerId = "p1", respondingPlayerId = "p2", isOrdago = true)
            for (phase in listOf(GamePhase.GRANDE, GamePhase.PARES, GamePhase.JUEGO)) {
                run {
                    val (gs, ai) = state(hand, phase, manoId = "p2", bet = normalBet)
                    lines += decide("RESP/$phase/$tag/bet/mano", gs, ai)
                }
                run {
                    val (gs, ai) = state(hand, phase, manoId = "p1", bet = normalBet)
                    lines += decide("RESP/$phase/$tag/bet/postre", gs, ai)
                }
                run {
                    val (gs, ai) = state(hand, phase, manoId = "p2", bet = ordago)
                    lines += decide("RESP/$phase/$tag/ordago/even", gs, ai)
                }
                run {
                    val (gs, ai) = state(hand, phase, manoId = "p2", myScore = 5, oppScore = 35, bet = ordago)
                    lines += decide("RESP/$phase/$tag/ordago/hailmary", gs, ai)
                }
            }
        }

        val actual = lines.joinToString("\n")
        File("build/ai-snapshot-actual.txt").apply { parentFile?.mkdirs() }.writeText(actual)
        assertEquals(EXPECTED.trim(), actual.trim())
    }

    companion object {
        // Capturado de master. Regenerar SOLO si la IA cambia a propósito.
        private val EXPECTED = """
MUS/4REY/mano=p2 => NoMus
MUS/4REY/mano=p4 => NoMus
DISCARD/4REY => ConfirmDiscard [ORE]
MUS/J31/mano=p2 => NoMus
MUS/J31/mano=p4 => NoMus
DISCARD/J31 => ConfirmDiscard [OSO]
MUS/DUP/mano=p2 => NoMus
MUS/DUP/mano=p4 => NoMus
DISCARD/DUP => ConfirmDiscard [ECI]
MUS/WEAK/mano=p2 => Mus
MUS/WEAK/mano=p4 => Mus
DISCARD/WEAK => ConfirmDiscard [BAS,CSO,ECU]
MUS/LOWC/mano=p2 => Mus
MUS/LOWC/mano=p4 => Mus
DISCARD/LOWC => ConfirmDiscard [BSE,CCU,ECI,OAS]
MUS/MED/mano=p2 => NoMus
MUS/MED/mano=p4 => NoMus
DISCARD/MED => ConfirmDiscard [BCU]
MUS/P7/mano=p2 => Mus
MUS/P7/mano=p4 => Mus
DISCARD/P7 => ConfirmDiscard [BCI,CSI,ECU,OSI]
MUS/PAS/mano=p2 => Mus
MUS/PAS/mano=p4 => Mus
DISCARD/PAS => ConfirmDiscard [BCI,ECU]
MUS/PREY/mano=p2 => Mus
MUS/PREY/mano=p4 => Mus
DISCARD/PREY => ConfirmDiscard [BCI,ECU]
OPEN/GRANDE/4REY/par => Envido(5)
OPEN/GRANDE/4REY/detras => Órdago
OPEN/GRANDE/4REY/delante => Envido(4)
OPEN/CHICA/4REY/par => Paso
OPEN/CHICA/4REY/detras => Paso
OPEN/CHICA/4REY/delante => Paso
OPEN/PARES/4REY/par => Envido(5)
OPEN/PARES/4REY/detras => Órdago
OPEN/PARES/4REY/delante => Envido(4)
OPEN/JUEGO/4REY/par => Paso
OPEN/JUEGO/4REY/detras => Paso
OPEN/JUEGO/4REY/delante => Paso
OPEN/GRANDE/J31/par => Paso
OPEN/GRANDE/J31/detras => Paso
OPEN/GRANDE/J31/delante => Paso
OPEN/CHICA/J31/par => Paso
OPEN/CHICA/J31/detras => Paso
OPEN/CHICA/J31/delante => Paso
OPEN/PARES/J31/par => Paso
OPEN/PARES/J31/detras => Paso
OPEN/PARES/J31/delante => Paso
OPEN/JUEGO/J31/par => Envido(5)
OPEN/JUEGO/J31/detras => Órdago
OPEN/JUEGO/J31/delante => Envido(4)
OPEN/GRANDE/DUP/par => Paso
OPEN/GRANDE/DUP/detras => Paso
OPEN/GRANDE/DUP/delante => Paso
OPEN/CHICA/DUP/par => Paso
OPEN/CHICA/DUP/detras => Paso
OPEN/CHICA/DUP/delante => Paso
OPEN/PARES/DUP/par => Envido(5)
OPEN/PARES/DUP/detras => Órdago
OPEN/PARES/DUP/delante => Envido(4)
OPEN/JUEGO/DUP/par => Paso
OPEN/JUEGO/DUP/detras => Paso
OPEN/JUEGO/DUP/delante => Paso
OPEN/GRANDE/WEAK/par => Paso
OPEN/GRANDE/WEAK/detras => Paso
OPEN/GRANDE/WEAK/delante => Paso
OPEN/CHICA/WEAK/par => Paso
OPEN/CHICA/WEAK/detras => Paso
OPEN/CHICA/WEAK/delante => Paso
OPEN/PARES/WEAK/par => Paso
OPEN/PARES/WEAK/detras => Paso
OPEN/PARES/WEAK/delante => Paso
OPEN/JUEGO/WEAK/par => Paso
OPEN/JUEGO/WEAK/detras => Paso
OPEN/JUEGO/WEAK/delante => Paso
OPEN/GRANDE/LOWC/par => Paso
OPEN/GRANDE/LOWC/detras => Paso
OPEN/GRANDE/LOWC/delante => Paso
OPEN/CHICA/LOWC/par => Paso
OPEN/CHICA/LOWC/detras => Paso
OPEN/CHICA/LOWC/delante => Paso
OPEN/PARES/LOWC/par => Paso
OPEN/PARES/LOWC/detras => Paso
OPEN/PARES/LOWC/delante => Paso
OPEN/JUEGO/LOWC/par => Paso
OPEN/JUEGO/LOWC/detras => Paso
OPEN/JUEGO/LOWC/delante => Paso
OPEN/GRANDE/MED/par => Paso
OPEN/GRANDE/MED/detras => Paso
OPEN/GRANDE/MED/delante => Paso
OPEN/CHICA/MED/par => Paso
OPEN/CHICA/MED/detras => Paso
OPEN/CHICA/MED/delante => Paso
OPEN/PARES/MED/par => Paso
OPEN/PARES/MED/detras => Paso
OPEN/PARES/MED/delante => Paso
OPEN/JUEGO/MED/par => Envido(5)
OPEN/JUEGO/MED/detras => Órdago
OPEN/JUEGO/MED/delante => Envido(4)
OPEN/GRANDE/P7/par => Paso
OPEN/GRANDE/P7/detras => Paso
OPEN/GRANDE/P7/delante => Paso
OPEN/CHICA/P7/par => Paso
OPEN/CHICA/P7/detras => Paso
OPEN/CHICA/P7/delante => Paso
OPEN/PARES/P7/par => Paso
OPEN/PARES/P7/detras => Paso
OPEN/PARES/P7/delante => Paso
OPEN/JUEGO/P7/par => Paso
OPEN/JUEGO/P7/detras => Paso
OPEN/JUEGO/P7/delante => Paso
OPEN/GRANDE/PAS/par => Paso
OPEN/GRANDE/PAS/detras => Paso
OPEN/GRANDE/PAS/delante => Paso
OPEN/CHICA/PAS/par => Paso
OPEN/CHICA/PAS/detras => Paso
OPEN/CHICA/PAS/delante => Paso
OPEN/PARES/PAS/par => Paso
OPEN/PARES/PAS/detras => Paso
OPEN/PARES/PAS/delante => Paso
OPEN/JUEGO/PAS/par => Paso
OPEN/JUEGO/PAS/detras => Paso
OPEN/JUEGO/PAS/delante => Paso
OPEN/GRANDE/PREY/par => Paso
OPEN/GRANDE/PREY/detras => Paso
OPEN/GRANDE/PREY/delante => Paso
OPEN/CHICA/PREY/par => Paso
OPEN/CHICA/PREY/detras => Paso
OPEN/CHICA/PREY/delante => Paso
OPEN/PARES/PREY/par => Paso
OPEN/PARES/PREY/detras => Paso
OPEN/PARES/PREY/delante => Paso
OPEN/JUEGO/PREY/par => Paso
OPEN/JUEGO/PREY/detras => Paso
OPEN/JUEGO/PREY/delante => Paso
RESP/GRANDE/4REY/bet/mano => Envido(5)
RESP/GRANDE/4REY/bet/postre => Envido(5)
RESP/GRANDE/4REY/ordago/even => Quiero
RESP/GRANDE/4REY/ordago/hailmary => Quiero
RESP/PARES/4REY/bet/mano => Envido(5)
RESP/PARES/4REY/bet/postre => Envido(5)
RESP/PARES/4REY/ordago/even => Quiero
RESP/PARES/4REY/ordago/hailmary => Quiero
RESP/JUEGO/4REY/bet/mano => NoQuiero
RESP/JUEGO/4REY/bet/postre => NoQuiero
RESP/JUEGO/4REY/ordago/even => NoQuiero
RESP/JUEGO/4REY/ordago/hailmary => Quiero
RESP/GRANDE/J31/bet/mano => NoQuiero
RESP/GRANDE/J31/bet/postre => NoQuiero
RESP/GRANDE/J31/ordago/even => NoQuiero
RESP/GRANDE/J31/ordago/hailmary => Quiero
RESP/PARES/J31/bet/mano => Quiero
RESP/PARES/J31/bet/postre => Quiero
RESP/PARES/J31/ordago/even => Quiero
RESP/PARES/J31/ordago/hailmary => Quiero
RESP/JUEGO/J31/bet/mano => Envido(5)
RESP/JUEGO/J31/bet/postre => Quiero
RESP/JUEGO/J31/ordago/even => Quiero
RESP/JUEGO/J31/ordago/hailmary => Quiero
RESP/GRANDE/DUP/bet/mano => NoQuiero
RESP/GRANDE/DUP/bet/postre => NoQuiero
RESP/GRANDE/DUP/ordago/even => NoQuiero
RESP/GRANDE/DUP/ordago/hailmary => Quiero
RESP/PARES/DUP/bet/mano => Envido(5)
RESP/PARES/DUP/bet/postre => Envido(5)
RESP/PARES/DUP/ordago/even => Quiero
RESP/PARES/DUP/ordago/hailmary => Quiero
RESP/JUEGO/DUP/bet/mano => NoQuiero
RESP/JUEGO/DUP/bet/postre => NoQuiero
RESP/JUEGO/DUP/ordago/even => NoQuiero
RESP/JUEGO/DUP/ordago/hailmary => Quiero
RESP/GRANDE/WEAK/bet/mano => NoQuiero
RESP/GRANDE/WEAK/bet/postre => NoQuiero
RESP/GRANDE/WEAK/ordago/even => NoQuiero
RESP/GRANDE/WEAK/ordago/hailmary => Quiero
RESP/PARES/WEAK/bet/mano => NoQuiero
RESP/PARES/WEAK/bet/postre => NoQuiero
RESP/PARES/WEAK/ordago/even => NoQuiero
RESP/PARES/WEAK/ordago/hailmary => Quiero
RESP/JUEGO/WEAK/bet/mano => NoQuiero
RESP/JUEGO/WEAK/bet/postre => NoQuiero
RESP/JUEGO/WEAK/ordago/even => NoQuiero
RESP/JUEGO/WEAK/ordago/hailmary => Quiero
RESP/GRANDE/LOWC/bet/mano => NoQuiero
RESP/GRANDE/LOWC/bet/postre => NoQuiero
RESP/GRANDE/LOWC/ordago/even => NoQuiero
RESP/GRANDE/LOWC/ordago/hailmary => Quiero
RESP/PARES/LOWC/bet/mano => NoQuiero
RESP/PARES/LOWC/bet/postre => NoQuiero
RESP/PARES/LOWC/ordago/even => NoQuiero
RESP/PARES/LOWC/ordago/hailmary => Quiero
RESP/JUEGO/LOWC/bet/mano => NoQuiero
RESP/JUEGO/LOWC/bet/postre => NoQuiero
RESP/JUEGO/LOWC/ordago/even => NoQuiero
RESP/JUEGO/LOWC/ordago/hailmary => Quiero
RESP/GRANDE/MED/bet/mano => NoQuiero
RESP/GRANDE/MED/bet/postre => NoQuiero
RESP/GRANDE/MED/ordago/even => NoQuiero
RESP/GRANDE/MED/ordago/hailmary => Quiero
RESP/PARES/MED/bet/mano => NoQuiero
RESP/PARES/MED/bet/postre => NoQuiero
RESP/PARES/MED/ordago/even => NoQuiero
RESP/PARES/MED/ordago/hailmary => Quiero
RESP/JUEGO/MED/bet/mano => Envido(5)
RESP/JUEGO/MED/bet/postre => Quiero
RESP/JUEGO/MED/ordago/even => Quiero
RESP/JUEGO/MED/ordago/hailmary => Quiero
RESP/GRANDE/P7/bet/mano => NoQuiero
RESP/GRANDE/P7/bet/postre => NoQuiero
RESP/GRANDE/P7/ordago/even => NoQuiero
RESP/GRANDE/P7/ordago/hailmary => Quiero
RESP/PARES/P7/bet/mano => NoQuiero
RESP/PARES/P7/bet/postre => NoQuiero
RESP/PARES/P7/ordago/even => NoQuiero
RESP/PARES/P7/ordago/hailmary => Quiero
RESP/JUEGO/P7/bet/mano => NoQuiero
RESP/JUEGO/P7/bet/postre => NoQuiero
RESP/JUEGO/P7/ordago/even => NoQuiero
RESP/JUEGO/P7/ordago/hailmary => Quiero
RESP/GRANDE/PAS/bet/mano => NoQuiero
RESP/GRANDE/PAS/bet/postre => NoQuiero
RESP/GRANDE/PAS/ordago/even => NoQuiero
RESP/GRANDE/PAS/ordago/hailmary => Quiero
RESP/PARES/PAS/bet/mano => NoQuiero
RESP/PARES/PAS/bet/postre => NoQuiero
RESP/PARES/PAS/ordago/even => NoQuiero
RESP/PARES/PAS/ordago/hailmary => Quiero
RESP/JUEGO/PAS/bet/mano => NoQuiero
RESP/JUEGO/PAS/bet/postre => NoQuiero
RESP/JUEGO/PAS/ordago/even => NoQuiero
RESP/JUEGO/PAS/ordago/hailmary => Quiero
RESP/GRANDE/PREY/bet/mano => NoQuiero
RESP/GRANDE/PREY/bet/postre => NoQuiero
RESP/GRANDE/PREY/ordago/even => NoQuiero
RESP/GRANDE/PREY/ordago/hailmary => Quiero
RESP/PARES/PREY/bet/mano => NoQuiero
RESP/PARES/PREY/bet/postre => NoQuiero
RESP/PARES/PREY/ordago/even => NoQuiero
RESP/PARES/PREY/ordago/hailmary => Quiero
RESP/JUEGO/PREY/bet/mano => NoQuiero
RESP/JUEGO/PREY/bet/postre => NoQuiero
RESP/JUEGO/PREY/ordago/even => NoQuiero
RESP/JUEGO/PREY/ordago/hailmary => Quiero
""".trimIndent()
    }
}
