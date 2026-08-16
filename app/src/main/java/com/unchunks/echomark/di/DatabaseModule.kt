package com.unchunks.echomark.di

import android.content.Context
import androidx.room.Room
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.LearningItemDao
import com.unchunks.echomark.data.local.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "echomark.db"
        )
            .fallbackToDestructiveMigration(dropAllTables = true) // TODO: リリーズ時に消す
            .build()
    }

    @Provides
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao {
        return database.bookmarkDao()
    }

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao {
        return database.tagDao()
    }

    @Provides
    fun provideLearningItemDao(database: AppDatabase): LearningItemDao {
        return database.learningItemDao()
    }

    @Provides
    fun provideCharMessageDao(database: AppDatabase): ChatMessageDao {
        return database.chatMessageDao()
    }
}
