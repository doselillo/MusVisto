package com.doselfurioso.musvisto.di

import com.doselfurioso.musvisto.logic.AILogger
import com.doselfurioso.musvisto.logic.ConsoleAILogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggerModule { // <-- CAMBIA a 'abstract class'

    @Binds // <-- CAMBIA @Provides por @Binds
    @Singleton
    abstract fun bindAILogger(
        consoleAILogger: ConsoleAILogger
    ): AILogger
}

// Mantenemos este módulo separado para las dependencias que sí creamos nosotros
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideRandom(): Random {
        return Random(System.currentTimeMillis())
    }
}