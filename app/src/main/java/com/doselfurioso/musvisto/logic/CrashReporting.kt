package com.doselfurioso.musvisto.logic

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Seam de telemetría de cuelgues. Aísla a los ViewModels de FirebaseCrashlytics
 * (igual que [FirebaseAuthGateway] aísla Auth) → testeables sin Android/Firebase.
 *
 * Postura RGPD: la recogida es **opt-out**, gobernada por
 * `GameSettings.crashReportingEnabled`. [MusVistoApp] la aplica al arrancar y el
 * toggle de Opciones la aplica al instante. Solo se integra Crashlytics (sin
 * Analytics) = minimización de datos: diagnósticos de cuelgue, no comportamiento.
 */
interface CrashReporting {
    /** Activa/desactiva la recogida de informes de cuelgues (persistente en el SDK). */
    fun setEnabled(enabled: Boolean)
}

/** Implementación real sobre Firebase Crashlytics. */
class FirebaseCrashReporting(
    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()
) : CrashReporting {
    override fun setEnabled(enabled: Boolean) {
        crashlytics.isCrashlyticsCollectionEnabled = enabled
    }
}

/** No-op para tests y para construir ViewModels sin tocar Firebase. */
object NoOpCrashReporting : CrashReporting {
    override fun setEnabled(enabled: Boolean) = Unit
}
