package com.unchunks.echomark.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room マイグレーションのテスト(Robolectric)。
 * スキーマ JSON(app/schemas)は build.gradle.kts で debug の assets に追加している。
 *
 * SupportSQLite 版の MigrationTestHelper は Windows だとパス区切り(\)の比較で失敗するため、
 * ドライバ版(AndroidSQLiteDriver)を使う。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        // Robolectric のテストごとの一時データディレクトリに作る(相対名だと作業ディレクトリに作られる)
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class
    )

    @Test
    fun migrate6To7_keepsRowsAndFillsDefaults() {
        helper.createDatabase(6).apply {
            insertV6Bookmark()
            close()
        }

        // スキーマ v7 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7))

        connection.prepare(
            "SELECT title, summary, imageUrl, siteName, isFavorite, isArchived FROM bookmarks WHERE id = 1"
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("既存のタイトル", stmt.getText(0))
            assertEquals("既存の要約", stmt.getText(1))
            assertTrue(stmt.isNull(2))
            assertTrue(stmt.isNull(3))
            assertEquals(0L, stmt.getLong(4))
            assertEquals(0L, stmt.getLong(5))
        }
        connection.close()
    }

    @Test
    fun migrate6To7_roomOpensWithAppMigrations() = runTest {
        helper.createDatabase(6).apply {
            insertV6Bookmark()
            close()
        }

        // アプリ(DatabaseModule)と同じマイグレーション一覧で開き、エンティティとして読めることを確認する
        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            TEST_DB
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        try {
            val entity = room.bookmarkDao().getById(1)
            assertNotNull(entity)
            assertEquals("既存のタイトル", entity!!.title)
            assertEquals(AiStatus.DONE, entity.aiStatus)
            assertNull(entity.imageUrl)
            assertNull(entity.siteName)
            assertFalse(entity.isFavorite)
            assertFalse(entity.isArchived)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate7To8_existingConversationsBecomeNormal() {
        helper.createDatabase(7).apply {
            execSQL(
                """
                INSERT INTO conversations (id, title, isTitleManuallySet, summary, createdAt, updatedAt)
                VALUES (1, '既存の会話', 1, NULL, 100, 200)
                """.trimIndent()
            )
            close()
        }

        val connection = helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8))

        connection.prepare("SELECT title, aboutBookmarkId FROM conversations WHERE id = 1").use { stmt ->
            assertTrue(stmt.step())
            assertEquals("既存の会話", stmt.getText(0))
            assertTrue(stmt.isNull(1))
        }
        connection.close()
    }

    @Test
    fun migrate6To8_roomOpensWithAppMigrations() = runTest {
        helper.createDatabase(6).apply {
            insertV6Bookmark()
            execSQL(
                """
                INSERT INTO conversations (id, title, isTitleManuallySet, summary, createdAt, updatedAt)
                VALUES (1, '既存の会話', 0, NULL, 100, 200)
                """.trimIndent()
            )
            close()
        }

        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            TEST_DB
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        try {
            val conversation = room.conversationDao().getById(1)
            assertNotNull(conversation)
            assertEquals("既存の会話", conversation!!.title)
            assertNull(conversation.aboutBookmarkId)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate8To9_existingTagsAndLinksBecomeUser() {
        helper.createDatabase(8).apply {
            insertV8Tags()
            close()
        }

        // スキーマ v9 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9))

        // 誰が付けたか分からない既存の紐付け・タグは、消されないようユーザーのものにする
        connection.prepare("SELECT source FROM bookmark_tag_cross_ref WHERE bookmarkId = 1 AND tagId = 1").use { stmt ->
            assertTrue(stmt.step())
            assertEquals("USER", stmt.getText(0))
        }
        connection.prepare("SELECT name, isUserCreated FROM tags ORDER BY id").use { stmt ->
            assertTrue(stmt.step())
            assertEquals("kotlin", stmt.getText(0))
            assertEquals(1L, stmt.getLong(1))
            assertTrue(stmt.step())
            assertEquals("未使用", stmt.getText(0))
            assertEquals(1L, stmt.getLong(1))
        }
        connection.close()
    }

    @Test
    fun migrate8To9_roomOpensAndKeepsUserTags() = runTest {
        helper.createDatabase(8).apply {
            insertV8Tags()
            close()
        }

        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            TEST_DB
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        try {
            val bookmark = room.bookmarkDao().getByIdsWithTags(listOf(1L)).single().toDomain()
            assertEquals(listOf("kotlin"), bookmark.tags)
            assertTrue(bookmark.aiTags.isEmpty())
            // 既存のタグはユーザーのタグなので、どこにも付いていなくても自動では消さない
            assertEquals(0, room.tagDao().deleteOrphanAiTags())
            assertEquals(listOf("kotlin", "未使用"), room.tagDao().getAllTags().first().map { it.name })
        } finally {
            room.close()
        }
    }

    @Test
    fun migrate9To10_existingBookmarksHaveNoFile() {
        helper.createDatabase(9).apply {
            insertV6Bookmark()
            close()
        }

        // スキーマ v10 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(10, listOf(MIGRATION_9_10))

        connection.prepare("SELECT title, filePath, mimeType, fileName, fileSize FROM bookmarks WHERE id = 1").use { stmt ->
            assertTrue(stmt.step())
            assertEquals("既存のタイトル", stmt.getText(0))
            (1..4).forEach { assertTrue(stmt.isNull(it)) }
        }
        connection.close()
    }

    @Test
    fun migrate6To10_roomOpensWithAppMigrations() = runTest {
        helper.createDatabase(6).apply {
            insertV6Bookmark()
            close()
        }

        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            TEST_DB
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        try {
            val bookmark = room.bookmarkDao().getByIdsWithTags(listOf(1L)).single().toDomain()
            assertEquals("既存のタイトル", bookmark.title)
            assertNull(bookmark.filePath)
            assertNull(bookmark.mimeType)
        } finally {
            room.close()
        }
    }

    /** v8 のブックマーク1件と、そこに付いたタグ・どこにも付いていないタグ。 */
    private fun SQLiteConnection.insertV8Tags() {
        insertV6Bookmark()
        execSQL("INSERT INTO tags (id, name) VALUES (1, 'kotlin'), (2, '未使用')")
        execSQL("INSERT INTO bookmark_tag_cross_ref (bookmarkId, tagId) VALUES (1, 1)")
    }

    private fun SQLiteConnection.insertV6Bookmark() {
        execSQL(
            """
            INSERT INTO bookmarks (id, type, content, contentUri, title, summary, category, createdAt, lastAccessedAt, aiStatus)
            VALUES (1, 'URL', NULL, 'https://example.com/a', '既存のタイトル', '既存の要約', NULL, 100, 200, 'DONE')
            """.trimIndent()
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
