/*

package com.doselfurioso.musvisto.logic

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton


// Usa SingletonComponent si tu versión de Hilt lo requiere
@Module
@InstallIn(SingletonComponent::class)
object AILoggerModule {


    // Cambia aquí la implementación por defecto: Console o File
    @Provides
    @Singleton
    fun provideAILogger(@ApplicationContext context: Context): AILogger {
// Por defecto: File logger (persistente) y también verás logs en Logcat
        return ConsoleAILogger()


// Si prefieres solo Logcat para desarrollo, usa:
// return ConsoleAILogger()
    }
}

 */