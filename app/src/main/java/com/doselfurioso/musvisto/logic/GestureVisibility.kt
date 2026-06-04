package com.doselfurioso.musvisto.logic

import com.doselfurioso.musvisto.model.GestureKind

/**
 * Predicado de "caza" de una seña RIVAL, compartido entre la IA y la redacción del
 * host (Fase 4.2). Un observador no siempre lee la seña del rival: solo a veces.
 *
 * **Determinista y ESTABLE dentro de una ronda** (no parpadea entre lances): depende
 * del seed `mano|emisor|observador|seña` —la mano rota cada ronda, así que se re-tira
 * solo al cambiar de ronda— y de la probabilidad [prob]. Al ser el MISMO predicado
 * con el MISMO seed, la IA (que decide sobre el estado autoritativo completo) y la
 * redacción del host (que decide qué seña rival ve un humano) **coinciden**: si la
 * mesa dice que cazas, cazas para todos los efectos.
 *
 * Extraído de `AILogic.opponentSignPerceived` (Fase 4.2): AILogic lo invoca con la
 * prob del perfil; la redacción del host, con [HUMAN_INTERCEPT_PROB] fija.
 */
object GestureVisibility {

    /** Prob. fija con que un observador HUMANO caza una seña rival online (decisión de feel 2026-06-04). */
    const val HUMAN_INTERCEPT_PROB = 0.20

    /** Resolución de la tirada: hashCode del seed → entero en [0, HASH_BUCKETS) → fracción en [0,1). */
    private const val HASH_BUCKETS = 1000

    fun perceivesOpponentSign(
        manoPlayerId: String,
        gesturerId: String,
        observerId: String,
        kind: GestureKind,
        prob: Double
    ): Boolean {
        val seed = "$manoPlayerId|$gesturerId|$observerId|${kind.name}"
        val r = seed.hashCode().mod(HASH_BUCKETS) / HASH_BUCKETS.toDouble()
        return r < prob
    }
}
