package com.unchunks.echomark.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.unchunks.echomark.BuildConfig
import com.unchunks.echomark.data.local.ALL_MIGRATIONS
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.DESTRUCTIVE_MIGRATION_FROM_VERSIONS
import com.unchunks.echomark.data.local.dao.ApiUsageDao
import com.unchunks.echomark.data.local.dao.BackupDao
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.dao.CategoryDao
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
            .applyMigrationPolicy(allowDestructiveDowngrade = BuildConfig.DEBUG) { boxStore.get().removeAllObjects() }
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
    fun provideCategoryDao(database: AppDatabase): CategoryDao {
        return database.categoryDao()
    }

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao {
        return database.conversationDao()
    }

    @Provides
    fun provideChatMessageDao(database: AppDatabase): ChatMessageDao {
        return database.chatMessageDao()
    }

    @Provides
    fun provideBackupDao(database: AppDatabase): BackupDao {
        return database.backupDao()
    }

    @Provides
    fun provideApiUsageDao(database: AppDatabase): ApiUsageDao {
        return database.apiUsageDao()
    }
}

/**
 * DB のマイグレーションの方針。DatabaseModule とテストで共有する。手順は docs/database-migrations.md。
 * - v6 以降は正式なマイグレーションでデータを保持する。マイグレーションが足りないときは作り直さず、開けずに失敗させる
 * - 開発初期(v1〜5、公開前の開発用ビルドだけ)からの更新は、データを作り直す
 * - ダウングレード(新しい版の DB を古いアプリで開く)は、[allowDestructiveDowngrade] のとき(debug ビルド)だけ作り直す。
 *   開発中はブランチを切り替えると版が下がるため作り直して開けるようにし、release では開けずに失敗させる
 *   (データは残るので、新しいアプリを入れ直せば元に戻る。versionCode を上げていない間は古い APK を上書きで入れられるため)
 *
 * データを作り直したときは [onDestructiveMigration] を呼ぶ。
 */
internal fun RoomDatabase.Builder<AppDatabase>.applyMigrationPolicy(
    allowDestructiveDowngrade: Boolean,
    onDestructiveMigration: () -> Unit
): RoomDatabase.Builder<AppDatabase> = this
    .addMigrations(*ALL_MIGRATIONS)
    .fallbackToDestructiveMigrationFrom(
        dropAllTables = true,
        *DESTRUCTIVE_MIGRATION_FROM_VERSIONS
    )
    .apply { if (allowDestructiveDowngrade) fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true) }
    .addCallback(object : RoomDatabase.Callback() {
        override fun onDestructiveMigration(connection: SQLiteConnection) {
            onDestructiveMigration()
        }
    })
