package com.unchunks.echomark.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.LearningItemDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.Converters
import com.unchunks.echomark.data.local.entity.LearningItemEntity
import com.unchunks.echomark.data.local.entity.TagEntity

@Database(
    entities = [
        BookmarkEntity::class, TagEntity::class, BookmarkTagCrossRef::class,
        LearningItemEntity::class, ChatMessageEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tagDao(): TagDao
    abstract fun learningItemDao(): LearningItemDao
    abstract fun chatMessageDao(): ChatMessageDao
}
