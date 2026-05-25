package com.doselfurioso.musvisto.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija la aritmética del objetivo de vaca (#29): chicos necesarios = ceil(N/2).
 * Es la parte más propensa a un off-by-one de toda la feature.
 */
class GameSettingsTest {

    @Test
    fun `mejor de 3 se gana con 2 chicos`() {
        assertEquals(2, GameSettings(bestOfChicos = 3).chicosToWinVaca)
    }

    @Test
    fun `mejor de 5 se gana con 3 chicos`() {
        assertEquals(3, GameSettings(bestOfChicos = 5).chicosToWinVaca)
    }

    @Test
    fun `un solo chico se gana con 1`() {
        assertEquals(1, GameSettings(bestOfChicos = 1).chicosToWinVaca)
    }

    @Test
    fun `valores por defecto - mejor de 3 a 40 tantos`() {
        val s = GameSettings()
        assertEquals(3, s.bestOfChicos)
        assertEquals(40, s.pointsPerChico)
        assertEquals(2, s.chicosToWinVaca)
    }
}
