package com.doselfurioso.musvisto

import android.app.Application
import com.doselfurioso.musvisto.logic.FirebaseCrashReporting
import com.doselfurioso.musvisto.logic.GameRepository

class MusVistoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // RGPD (opt-out): aplica el consentimiento guardado ANTES de que cualquier
        // cuelgue temprano pueda reportarse. Por defecto activo (interés legítimo:
        // estabilidad); el usuario lo apaga en Opciones. Firebase ya está inicializado
        // aquí (lo arranca su ContentProvider antes de Application.onCreate).
        val settings = GameRepository(this).loadSettings()
        FirebaseCrashReporting().setEnabled(settings.crashReportingEnabled)
    }
}
