package com.doselfurioso.musvisto.debug

import androidx.compose.runtime.Composable
import com.doselfurioso.musvisto.presentation.GameViewModel

/**
 * Stub no-op para builds de release.
 *
 * Esta versión vive en `src/release/` y se compila SOLO en builds release.
 * La versión real (`src/debug/.../DebugFeatures.kt`) se compila solo en builds debug.
 * El código de `src/main/` referencia `DebugFeatures` sin saber qué implementación
 * recibe; el sistema de build variants resuelve la correcta.
 */
object DebugFeatures {
    const val IS_ENABLED = false

    @Composable
    fun AiDebugPanelOverlay(viewModel: GameViewModel) {
        // No-op en release.
    }

    @Composable
    fun DebugToggleButton(viewModel: GameViewModel) {
        // No-op en release.
    }
}
