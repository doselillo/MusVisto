package com.doselfurioso.musvisto.presentation


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.logic.DebugLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(navController: NavController) {
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs de Depuración") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    val fullLog = DebugLogger.logs.joinToString("\n\n")
                    clipboardManager.setText(AnnotatedString(fullLog))
                }) {
                    Text("Copiar Logs")
                }
                Button(onClick = { DebugLogger.clear() }) {
                    Text("Limpiar Logs")
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(DebugLogger.logs) { logMsg ->
                    Text(logMsg, style = MaterialTheme.typography.bodySmall)
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}