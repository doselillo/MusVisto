package com.doselfurioso.musvisto.presentation

import com.doselfurioso.musvisto.model.GameSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolución de nombre del lobby online (fix "el nombre sale 'Tú'"). El placeholder
 * offline por defecto se trata como "sin nombre" y nunca se filtra a la sala.
 */
class LobbyNameTest {

    @Test
    fun `el placeholder por defecto precarga vacio`() {
        assertEquals("", initialLobbyName(GameSettings().humanName))
    }

    @Test
    fun `un nombre real se precarga recortado`() {
        assertEquals("Dani", initialLobbyName("Dani"))
        assertEquals("Dani", initialLobbyName("  Dani  "))
    }

    @Test
    fun `vacio o solo espacios precarga vacio`() {
        assertEquals("", initialLobbyName(""))
        assertEquals("", initialLobbyName("   "))
    }

    @Test
    fun `nombre en blanco cae a Jugador, nunca a Tu`() {
        assertEquals("Jugador", effectiveLobbyName(""))
        assertEquals("Jugador", effectiveLobbyName("   "))
    }

    @Test
    fun `el nombre efectivo va recortado`() {
        assertEquals("Dani", effectiveLobbyName("  Dani "))
    }
}
