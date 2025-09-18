package com.doselfurioso.musvisto.logic

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

// Usamos un 'object' para crear un Singleton. Esto asegura que solo haya una
// instancia del logger y que la lista de logs sea la misma en toda la app.
object DebugLogger {

    // Una lista observable que se actualizará automáticamente en la UI de Compose.
    val logs = mutableStateListOf<String>()

    // Función para añadir un nuevo mensaje de log a la lista.
    fun log(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        logs.add(0, "$timestamp [$tag]: $message") // Añade al principio para ver los más nuevos arriba

        // Para evitar que la lista crezca indefinidamente y consuma mucha memoria.
        if (logs.size > 200) {
            logs.removeAt(logs.lastIndex)
        }
    }

    // Función para limpiar todos los logs.
    fun clear() {
        logs.clear()
    }
}