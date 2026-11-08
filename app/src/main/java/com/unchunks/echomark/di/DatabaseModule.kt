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
import com.unchunks.echomark.data.local.dao.BookmarkNoteDao
import com.unchunks.echomark.data.local.dao.CollectionDao
import com.unchunks.echomark.data.local.dao.LinkCheckDao
import com.unchunks.echomark.data.local.dao.ReviewCardDao
import com.unchunks.echomark.data.local.dao.SmartFolderDao
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.components.securestorage.KeystoreSecretCipher
import com.unchunks.echomark.data.local.encryption.DatabaseEncryptionMigrator
import com.unchunks.echomark.data.local.encryption.DatabaseEncryptionStatus
import com.unchunks.echomark.data.local.encryption.DatabaseKeyStore
import com.unchunks.echomark.data.local.encryption.DatabaseLeftovers
import com.unchunks.echomark.data.local.encryption.EncryptingOpenHelperFactory
import com.unchunks.echomark.data.local.encryption.EncryptionOutcome
import com.unchunks.echomark.data.local.encryption.SqlCipherEngine
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** DB ファイル名。設定画面のストレージ使用量の計算でも使う。 */
    const val DATABASE_NAME = "echomark.db"

    /** DB の鍵(パスフレーズ)を暗号化する Android Keystore の鍵の名前。一度使い始めたら変えない(変えると DB を開けなくなる) */
    private const val DATABASE_KEY_ALIAS = "echomark_database_key"

    /** DB の鍵の暗号文のファイル(バックアップされない場所) */
    private const val DATABASE_KEY_FILE = "database_key.bin"

    /** DB の暗号化の準備(#100)。平文の DB の暗号化と、残った平文の写しの片付けを行う */
    @Provides
    @Singleton
    fun provideDatabaseEncryptionMigrator(@ApplicationContext context: Context): DatabaseEncryptionMigrator =
        DatabaseEncryptionMigrator(
            main = context.getDatabasePath(DATABASE_NAME),
            engine = SqlCipherEngine(),
            keyStore = DatabaseKeyStore(File(context.noBackupFilesDir, DATABASE_KEY_FILE), KeystoreSecretCipher(DATABASE_KEY_ALIAS))
        )

    @Provides
    fun provideDatabaseLeftovers(migrator: DatabaseEncryptionMigrator): DatabaseLeftovers = migrator

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        boxStore: Lazy<BoxStore>,
        migrator: DatabaseEncryptionMigrator,
        encryptionStatus: DatabaseEncryptionStatus
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME
        )
            // 保存したデータを SQLCipher で暗号化する(#100)。最初に開くとき(裏のスレッド)に、平文の DB を暗号化する
            .openHelperFactory(
                EncryptingOpenHelperFactory(prepare = migrator::prepare) { outcome ->
                    encryptionStatus.record(outcome)
                    // 鍵が失われて DB を作り直したら、古い埋め込みが新しいブックマークに紐付かないよう消す
                    if (outcome is EncryptionOutcome.KeyLost) boxStore.get().removeAllObjects()
                }
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
    fun provideSubscriptionDao(database: AppDatabase): SubscriptionDao {
        return database.subscriptionDao()
    }

    @Provides
    fun provideLinkCheckDao(database: AppDatabase): LinkCheckDao {
        return database.linkCheckDao()
    }

    @Provides
    fun provideReviewCardDao(database: AppDatabase): ReviewCardDao {
        return database.reviewCardDao()
    }

    @Provides
    fun provideCollectionDao(database: AppDatabase): CollectionDao {
        return database.collectionDao()
    }

    @Provides
    fun provideBookmarkNoteDao(database: AppDatabase): BookmarkNoteDao {
        return database.bookmarkNoteDao()
    }

    @Provides
    fun provideSmartFolderDao(database: AppDatabase): SmartFolderDao {
        return database.smartFolderDao()
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
