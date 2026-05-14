package com.doselfurioso.musvisto.debug

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doselfurioso.musvisto.presentation.GameViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Implementación real de las features de debug, solo en builds de tipo `debug`.
 * El homónimo en `src/release/` es un stub no-op.
 */
object DebugFeatures {
    const val IS_ENABLED = true

    /**
     * Overlay flotante en la esquina inferior derecha con el log de las últimas
     * decisiones de la IA. Solo se renderiza si el usuario activó el modo debug
     * desde el menú de pausa.
     */
    @Composable
    fun AiDebugPanelOverlay(viewModel: GameViewModel) {
        val isDebugMode by viewModel.isDebugMode.collectAsState()
        if (!isDebugMode) return

        val logs by viewModel.aiDebugLogs.collectAsState()
        Box(modifier = Modifier.fillMaxSize()) {
            AiDebugPanel(
                logs = logs,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
        }
    }

    /** Botón "🐛 Debug ON/OFF" para incluir dentro del menú de pausa. */
    @Composable
    fun DebugToggleButton(viewModel: GameViewModel) {
        val isDebugMode by viewModel.isDebugMode.collectAsState()
        Button(
            onClick = { viewModel.onToggleDebugMode() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDebugMode) Color(0xFF00C853) else Color(0xFF424242)
            )
        ) {
            Text(text = if (isDebugMode) "🐛 Debug: ON" else "🐛 Debug: OFF")
        }
    }
}

@Composable
private fun AiDebugPanel(logs: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (expanded) "▼ AI Log" else "▲ AI Log",
                color = Color(0xFF00FF88),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .width(320.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1565C0).copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .clickable(enabled = logs.isNotEmpty()) { exportAiDebugLog(context, logs) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "↓ Exportar",
                        color = if (logs.isNotEmpty()) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 420.dp)
                    .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { log ->
                    val index = logs.indexOf(log)
                    val isLatest = index == 0
                    Column {
                        Text(
                            text = if (isLatest) "— Última decisión —" else "— Decisión ${index + 1} anterior —",
                            color = if (isLatest) Color(0xFF00FF88) else Color.Gray,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = log,
                            color = Color.White,
                            fontSize = 9.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun exportAiDebugLog(context: Context, logs: List<String>) {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val sb = StringBuilder()
    sb.appendLine("=== MusVisto — AI Debug Log ===")
    sb.appendLine("Exportado: $timestamp")
    sb.appendLine("Decisiones registradas: ${logs.size}")
    sb.appendLine("=".repeat(40))
    sb.appendLine()
    logs.forEachIndexed { index, log ->
        val label = if (index == 0) "ÚLTIMA DECISIÓN" else "DECISIÓN ${index + 1} ANTERIOR"
        sb.appendLine("--- $label ---")
        sb.appendLine(log)
        sb.appendLine()
    }

    // Compartimos el texto directamente (sin FileProvider) para no depender de
    // declarar el provider en AndroidManifest.
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, sb.toString())
        putExtra(Intent.EXTRA_SUBJECT, "MusVisto AI Debug Log $timestamp")
    }
    context.startActivity(Intent.createChooser(intent, "Compartir log de IA"))
}
