package com.unchunks.echomark.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.unchunks.echomark.data.local.dao.ApiUsageDao
import com.unchunks.echomark.data.local.dao.BackupDao
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.data.local.entity.ApiUsageEntity
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.Converters
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.entity.SummaryHistoryEntity
import com.unchunks.echomark.data.local.entity.TagEntity

@Database(
    entities = [
        BookmarkEntity::class, TagEntity::class, BookmarkTagCrossRef::class,
        ConversationEntity::class, ChatMessageEntity::class, ApiUsageEntity::class, SummaryHistoryEntity::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tagDao(): TagDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun backupDao(): BackupDao
    abstract fun apiUsageDao(): ApiUsageDao

    companion object {
        /**
         * DB のスキーマの版。テーブル・列・インデックスを変えたら 1 つ上げ、Migrations.kt にマイグレーションを足す
         * (手順は docs/database-migrations.md)。
         */
        const val VERSION = 15
    }
}
