package com.unchunks.echomark.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 会話一覧のクエリ(質問の対象のブックマーク名)のテスト。 */
@RunWith(AndroidJUnit4::class)
class ConversationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.conversationDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun 一覧には質問の対象のブックマーク名が付き_削除済みや通常の会話はnull() = runTest {
        val bookmarkId = db.bookmarkDao().insert(
            BookmarkEntity(
                type = BookmarkType.URL, contentUri = "https://a", title = "Compose のヒント",
                createdAt = 1, lastAccessedAt = 1
            )
        )
        dao.insert(ConversationEntity(title = "について", createdAt = 1, updatedAt = 3, aboutBookmarkId = bookmarkId))
        dao.insert(ConversationEntity(title = "削除済み", createdAt = 1, updatedAt = 2, aboutBookmarkId = 999))
        dao.insert(ConversationEntity(title = "通常", createdAt = 1, updatedAt = 1))

        val rows = dao.observeAllWithLastMessage().first()

        assertEquals(
            listOf("について" to "Compose のヒント", "削除済み" to null, "通常" to null),
            rows.map { it.conversation.title to it.aboutBookmarkTitle }
        )
    }
}
