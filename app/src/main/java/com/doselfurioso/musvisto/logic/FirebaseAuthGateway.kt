package com.doselfurioso.musvisto.logic

import com.google.firebase.auth.FirebaseAuth

/**
 * Identidad de red mínima del multijugador: sesión **anónima** de Firebase Auth
 * (Fase 1, docs/context/MULTIPLAYER_PLAN.md). Las reglas de RTDB exigen
 * `auth != null`; este helper garantiza un `uid` estable por dispositivo sin
 * pedir cuenta. Google Sign-In (persistencia entre dispositivos) sería una
 * mejora opcional de Fase 5.
 */
class FirebaseAuthGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    /** `uid` de la sesión actual, o `null` si aún no se ha iniciado sesión. */
    val currentUid: String? get() = auth.currentUser?.uid

    /**
     * Garantiza una sesión anónima. Si ya hay usuario, devuelve su `uid` sin
     * tocar la red; si no, inicia sesión anónima. [onResult] recibe el `uid` en
     * éxito o la excepción en fallo.
     */
    fun ensureSignedIn(onResult: (Result<String>) -> Unit) {
        val existing = auth.currentUser
        if (existing != null) {
            onResult(Result.success(existing.uid))
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { authResult ->
                val uid = authResult.user?.uid
                onResult(
                    if (uid != null) Result.success(uid)
                    else Result.failure(IllegalStateException("Sesión anónima sin uid"))
                )
            }
            .addOnFailureListener { error -> onResult(Result.failure(error)) }
    }
}
