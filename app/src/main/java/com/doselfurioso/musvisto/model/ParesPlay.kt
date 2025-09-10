package com.doselfurioso.musvisto.model

import kotlinx.serialization.Serializable

/**
 * Represents the possible plays in the "Pares" lance, ordered by strength.
 */
@Serializable
sealed class ParesPlay(val strength: Int) {
    // 1. Duples (two pairs) - Highest strength
    @Serializable
    data class Duples(val highPair: Rank, val lowPair: Rank) : ParesPlay(3)

    // 2. Medias (three of a kind)
    @Serializable
    data class Medias(val rank: Rank) : ParesPlay(2)

    // 3. Pares (one pair) @Serializable
    @Serializable
    data class Pares(val rank: Rank) : ParesPlay(1)

    // 4. No Pares (nothing) - Lowest strength
    @Serializable
    object NoPares : ParesPlay(0)
}