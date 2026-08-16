package com.unchunks.echomark.di

import android.content.Context
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity
import com.unchunks.echomark.data.local.objectbox.MyObjectBox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.Box
import io.objectbox.BoxStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ObjectBoxModule {
    @Provides
    @Singleton
    fun provideBoxStore(@ApplicationContext context: Context): BoxStore {
        return MyObjectBox.builder()
            .androidContext(context)
            .build()
    }

    @Provides
    fun provideEmbeddingBox(boxStore: BoxStore): Box<EmbeddingEntity> {
        return boxStore.boxFor(EmbeddingEntity::class.java)
    }
}
