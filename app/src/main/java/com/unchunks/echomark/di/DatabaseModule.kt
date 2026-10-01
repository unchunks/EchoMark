package com.unchunks.echomark.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.unchunks.echomark.data.local.ALL_MIGRATIONS
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.DESTRUCTIVE_MIGRATION_FROM_VERSIONS
import com.unchunks.echomark.data.local.dao.BackupDao
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.dao.TagDao
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** DB ファイル名。設定画面のストレージ使用量の計算でも使う。 */
    const val DATABASE_NAME = "echomark.db"

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        boxStore: Lazy<BoxStore>
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME
        )
            // 作り直したブックマークの ID は 1 から振り直されるため、古い埋め込みが新しいブックマークに紐付かないよう消す
            .applyMigrationPolicy { boxStore.get().removeAllObjects() }
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

    @Provides
    fun provideBackupDao(database: AppDatabase): BackupDao {
        return database.backupDao()
    }
}

/**
 * DB のマイグレーションの方針。DatabaseModule とテストで共有する。
 * - v6 以降は正式なマイグレーションでデータを保持する
 * - 開発初期(v1〜5)からの更新とダウングレードだけは、データを作り直す。そのときは [onDestructiveMigration] を呼ぶ
 */
internal fun RoomDatabase.Builder<AppDatabase>.applyMigrationPolicy(
    onDestructiveMigration: () -> Unit
): RoomDatabase.Builder<AppDatabase> = this
    .addMigrations(*ALL_MIGRATIONS)
    .fallbackToDestructiveMigrationFrom(
        dropAllTables = true,
        *DESTRUCTIVE_MIGRATION_FROM_VERSIONS
    )
    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    .addCallback(object : RoomDatabase.Callback() {
        override fun onDestructiveMigration(connection: SQLiteConnection) {
            onDestructiveMigration()
        }
    })
