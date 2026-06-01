package com.doselfurioso.musvisto.logic

/**
 * Perfil de personalidad de una IA (#34): agrupa TODAS las palancas de
 * comportamiento de [AILogic] que antes eran `const val` a nivel de fichero.
 *
 * Los **defaults son EXACTAMENTE los valores baseline ya validados** → `AIProfile()`
 * reproduce la IA actual bit a bit. Cada [AILogic] recibe un perfil; los rivales
 * dejan de ser clones.
 *
 * **Principio (no negociar):** los arquetipos son modificadores CAPADOS sobre ese
 * baseline; ni el más flojo debe "parecer tonto". Cada arquetipo con valores
 * propios (Fase C) se valida con el simulador + reviewers + playtest antes de
 * mergear.
 *
 * Ejes de personalidad (app rival) → campos de este perfil:
 *  - **Osadía**     → [openStrongValue], [openMidBandFloor], [supportOwnFloor], pisos de órdago
 *  - **Faroleo**    → [bluffProbPostre] / [bluffProbPenultimate] / [bluffProbEarly]
 *  - **Caza señas** → [opponentSignInterceptProb]
 *  - **Corta mus**  → [musCutParesJuego] / [musCutGrandeChica] / [musFatigueStep]
 *  - **Descartes**  → [discardThreshold] / [duplesBonus] / [deadPenalty]
 *  - **Interactividad** → flavor de UI (emotes), NO se modela aquí.
 */
// Esta data class es, por definición, un registro de números de calibración
// (los antiguos `const val` de AILogic). Silenciar MagicNumber aquí es correcto:
// los literales SON el dato, documentado campo a campo.
@Suppress("MagicNumber")
data class AIProfile(
    // ----- Apertura / Osadía -----
    /**
     * Apertura del primero: solo abro yo con mano ultra-premium (>90: 4R/31
     * mano/duples reyes mano); con el resto apoyo y deja decidir al capitán.
     * Más bajo → el primero abre manos medias y la pareja se "pisa".
     */
    val supportOwnFloor: Int = 90,
    /** Banda de "valor seguro": fuerza > este umbral abre envite de valor. */
    val openStrongValue: Int = 78,
    /** Piso de la banda media probabilística de apertura. */
    val openMidBandFloor: Int = 55,

    // ----- Faroleo -----
    // Farol de robo (importe 2) cuando alguien ya pasó y el marcador no es
    // delicado. Se gradúa por `rivalsBehind` = rivales del lance que actúan
    // DESPUÉS de mí (usuario 2026-05-22: nada de "nunca" categórico; humano).
    /** rivalsBehind == 0 (postre / sin rivales detrás): robo pleno. */
    val bluffProbPostre: Double = 0.20,
    /** rivalsBehind == 1 (un rival aún por hablar, puede cazarme): prudente. */
    val bluffProbPenultimate: Double = 0.08,
    /** rivalsBehind >= 2: muy raro, pero NO absoluto. */
    val bluffProbEarly: Double = 0.05,

    // ----- Caza de señas -----
    /**
     * Probabilidad de INTERCEPTAR una seña del equipo contrario (#7). Baja: en
     * el Mus real solo se cazan algunas. (Antes 100% = omnisciencia.)
     */
    val opponentSignInterceptProb: Double = 0.20,

    // ----- Corte de Mus -----
    /** Fuerza mínima para cortar Mus (NoMus) en Pares/Juego. */
    val musCutParesJuego: Int = 75,
    /** Fuerza mínima para cortar Mus (NoMus) en Grande/Chica. */
    val musCutGrandeChica: Int = 85,
    /**
     * Si el COMPAÑERO es mano, sube el listón de corte (no quitarle la mano).
     * Señal, no veto: 6 (bajado de 10 en playtest).
     */
    val partnerManoMusBias: Int = 6,
    /**
     * #37 "Fatiga de Mus": cada ciclo Mus+descarte sin cortar baja el umbral de
     * corte en este paso → el bucle de Mus TERMINA. Ronda 0 sin fatiga (Mus
     * legítimo del refrán). Solo la IA (el humano corta a mano).
     */
    val musFatigueStep: Int = 8,
    /**
     * #20 Capitanía delegada de Mus: % de "break" para no ser absoluto al delegar
     * el corte al compañero humano (humano-like). Con compañero IA es no-op.
     */
    val musDelegationBreakPct: Int = 5,

    // ----- Descartes -----
    /** Se descartan las cartas con score < este umbral. */
    val discardThreshold: Int = 25,
    /** Bonus de score a cartas que forman duples al evaluar el descarte. */
    val duplesBonus: Int = 30,
    /** Penalización de score a cartas del rango muerto (4-7) al descartar. */
    val deadPenalty: Int = 25,

    // ----- Respuesta / showdown -----
    /**
     * Penalización al strength del capitán cuando responde a un envite y su
     * compañero (primero del equipo) YA pasó: el equipo apuesta solo con la mano
     * del capitán. 15 saca las manos 75-85 de la banda Quiero (las que sangraban).
     */
    val captainAloneResponsePenalty: Int = 15,
    /**
     * Gate Hail-Mary de respuesta a órdago (#33): por debajo de esta fuerza
     * asumo que un envite YA QUERIDO pendiente en OTRO lance lo gana el rival.
     * Sesgo SEGURO a subcontar. Usa mi fuerza ajustada por señas; nunca cartas del rival.
     */
    val pendingLanceLossThreshold: Int = 50,
    /** #23: strength efectivo de Grande para duples-de-reyes propio siendo mano. */
    val duplesReyManoGrandeStrength: Int = 82,

    // ----- Órdago endgame (#16, ver docs/context/ORDAGO_STRATEGY.md) -----
    /** Piso de mano FUERTE para R5 (endgame ajustado, diff <= 2). */
    val endgameOrdagoTightFloor: Int = 85,
    /** Piso para Q2 (último lance apostable, diff ∈ [3,5]). */
    val endgameOrdagoLastLanceFloor: Int = 85,
    /** Máximo diff de marcador para disparar Q2. */
    val q2MaxDiff: Int = 5,
    /** Piso para R1.a "mano excelente" en desventaja crítica sin proxy (4R/duples reyes/31 mano). */
    val endgameOrdagoHailmaryFloor: Int = 90,
    /** R1.a "diferencia AMPLIA": diff ≤ este valor (evita disparar con diff -1/-4). */
    val r1aAmpleDiff: Int = -5,
    /** Piso para R1.a' "proxy rival flojo" (rival pidió Mus + descartó muchas). */
    val endgameOrdagoHailmaryLooseFloor: Int = 70,
    /** R1.a'' "Desesperación catastrófica": diff ≤ este valor + opp ≥ 33. */
    val endgameCatastropheDiff: Int = -15,
    /** Piso de mano remotamente jugable para R1.a''. */
    val endgameOrdagoCatastropheFloor: Int = 60,
    /** Q3 proxy: nº mínimo de cartas descartadas por el rival para considerarlo "flojo". */
    val opponentLooseDiscardMin: Int = 3,
    /** Si el compañero señalizó FUERTE, los pisos del módulo de órdago bajan este bono. */
    val endgameOrdagoPairHintBonus: Int = 5,
    /** Frontera de "endgame ajustado": ambos en zona de cierre (>= este score). */
    val endgameBorderScore: Int = 33,
    /** Diferencia mínima (un envite estándar) para R5/R4.f en endgame ajustado. */
    val endgameTightDiff: Int = 2,
    /** Envido estándar con el que un equipo a >=33 cerraría 40 (proyección R1.b). */
    val endgameStandardCloseBet: Int = 4,
    /** "Mano decente para lances posteriores" en el gate Q2/R5. */
    val endgameRemainingLanceFloor: Int = 70,
    /** R1.b: piso por debajo del cual asumo que el rival GANA el showdown del lance (overcontar, cauto). */
    val r1bOpponentWinThreshold: Int = 55,
    /** R1.b: mi mano mínima para que el órdago de "cortar la jugada" sea razonable. */
    val r1bMyHandFloor: Int = 80,
    /** En respuesta a órdago, si el compañero señalizó FUERTE el acceptThreshold baja este valor (R4.d). */
    val ordagoResponsePartnerHintBonus: Int = 8,
    /** R4.e: si el rival lanzó un envido fuerte/órdago previo, el acceptThreshold sube este valor. */
    val ordagoResponseOppStrongBetPenalty: Int = 5,
    /** Importe a partir del cual un envido del rival cuenta como "fuerte". */
    val strongBetThreshold: Int = 4,
    /** R4.f: en endgame ajustado el acceptThreshold baja un poco (el cobro inmediato me deja cerrar). */
    val ordagoResponseTightEndgameBonus: Int = 3,
) {
    companion object {
        /** Baseline ya validado (= valores actuales de producción). */
        val EQUILIBRADO = AIProfile()

        // Fase A (arquitectura): los arquetipos son IGUALES al baseline; sus
        // deltas reales se calibran en Fase C con el simulador + reviewers +
        // playtest. Mantenerlos como copias explícitas deja el sitio listo y
        // documenta la intención sin cambiar el comportamiento todavía.
        /**
         * Osadía↑, corta-mus↑ (#34 Fase C, v2 SUTIL). Deltas DELIBERADAMENTE
         * pequeños (decisión del usuario 2026-06-01): la prioridad es NO romper la
         * IA —que ya juega bien— sobre una diferenciación marcada; un humano nota
         * "un toque", no otro motor. openStrongValue 78→75, openMidBandFloor 55→53
         * (solo −2: el reviewer avisó que la banda media ya se percibe agresiva,
         * #11), musea menos (musCut* −3). NO toca el lado RESPUESTA (no reabrir la
         * timidez #1) ni los pisos de órdago (#16/#33). Validación: sim como red de
         * seguridad + mus-strategy-reviewer + playtest (árbitro del feel; la
         * apertura es punto ciego del sim).
         */
        val AGRESIVO = AIProfile(
            openStrongValue = 75,
            openMidBandFloor = 53,
            musCutParesJuego = 72,
            musCutGrandeChica = 82
        )
        /** Osadía↓, musea más; SIN sobre-plegarse al farol (cap, no reabrir #1). */
        val CONSERVADOR = AIProfile()
        /** Faroleo↑, banda media más ancha (deltas en Fase C). */
        val FAROLERO = AIProfile()
    }
}
