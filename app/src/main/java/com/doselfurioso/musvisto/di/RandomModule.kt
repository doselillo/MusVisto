package com.doselillo.musvisto.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
object RandomModule {
    @Provides
    @Singleton
    fun provideRandom(): Random {
        // Proporciona una instancia de Random verdaderamente aleatoria
        return Random(System.currentTimeMillis())
    }
}