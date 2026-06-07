package com.doselfurioso.musvisto.presentation

import com.doselfurioso.musvisto.logic.CrashReporting
import com.doselfurioso.musvisto.logic.GameStore
import com.doselfurioso.musvisto.model.GameSettings
import com.doselfurioso.musvisto.model.SaveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consentimiento de telemetría de cuelgues (Crashlytics, opt-out RGPD). El toggle
 * de Opciones debe aplicar el cambio al SDK AL INSTANTE y persistirlo, para que
 * apagarlo deje de recoger ya (no solo en el próximo arranque).
 */
class CrashConsentTest {

    private class FakeStore(private var settings: GameSettings = GameSettings()) : GameStore {
        override fun saveState(saveState: SaveState) = Unit
        override fun loadState(): SaveState? = null
        override fun deleteState() = Unit
        override fun saveSettings(settings: GameSettings) { this.settings = settings }
        override fun loadSettings(): GameSettings = settings
    }

    private class FakeCrashReporting : CrashReporting {
        var lastEnabled: Boolean? = null
        override fun setEnabled(enabled: Boolean) { lastEnabled = enabled }
    }

    @Test
    fun `por defecto la telemetria esta activada (opt-out)`() {
        assertTrue(GameSettings().crashReportingEnabled)
        val vm = MainMenuViewModel(FakeStore(), FakeCrashReporting())
        assertTrue(vm.settings.value.crashReportingEnabled)
    }

    @Test
    fun `apagar la telemetria aplica al SDK y persiste al instante`() {
        val store = FakeStore()
        val reporter = FakeCrashReporting()
        val vm = MainMenuViewModel(store, reporter)

        vm.setCrashReporting(false)

        assertEquals(false, reporter.lastEnabled)
        assertFalse(vm.settings.value.crashReportingEnabled)
        assertFalse(store.loadSettings().crashReportingEnabled)
    }

    @Test
    fun `volver a encender propaga true al SDK y al disco`() {
        val store = FakeStore(GameSettings(crashReportingEnabled = false))
        val reporter = FakeCrashReporting()
        val vm = MainMenuViewModel(store, reporter)

        vm.setCrashReporting(true)

        assertEquals(true, reporter.lastEnabled)
        assertTrue(vm.settings.value.crashReportingEnabled)
        assertTrue(store.loadSettings().crashReportingEnabled)
    }
}
