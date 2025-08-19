package com.doselfurioso.musvisto.model

/**
 * Represents the possible plays in the "Pares" lance, ordered by strength.
 */
sealed class ParesPlay(val strength: Int) {
    // 1. Duples (two pairs) - Highest strength
    data class Duples(val highPair: Rank, val lowPair: Rank) : ParesPlay(3)

    // 2. Medias (three of a kind)
    data class Medias(val rank: Rank) : ParesPlay(2)

    // 3. Pares (one pair)
    data class Pares(val rank: Rank) : ParesPlay(1)

    // 4. No Pares (nothing) - Lowest strength
    object NoPares : ParesPlay(0)
}