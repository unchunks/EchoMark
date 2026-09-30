package com.unchunks.echomark.di

import android.content.Context
import androidx.room.Room
import com.unchunks.echomark.data.local.ALL_MIGRATIONS
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.DESTRUCTIVE_MIGRATION_FROM_VERSIONS
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
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
            // v6 以降は正式なマイグレーションでデータを保持する
            .addMigrations(*ALL_MIGRATIONS)
            // 開発初期(v1〜5)からの更新とダウングレードだけは、データを作り直す
            .fallbackToDestructiveMigrationFrom(
                dropAllTables = true,
                *DESTRUCTIVE_MIGRATION_FROM_VERSIONS
            )
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
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
    fun provideConversationDao(database: AppDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideCharMessageDao(database: AppDatabase): ChatMessageDao {
        return database.chatMessageDao()
    }
}
