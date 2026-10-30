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
    fun migrate10To11_urlsWithContentAreTreatedAsFetched() {
        helper.createDatabase(10).apply {
            execSQL(
                """
                INSERT INTO bookmarks (id, type, content, contentUri, title, createdAt, lastAccessedAt, aiStatus)
                VALUES (1, 'URL', '本文', 'https://example.com/a', 'a', 100, 100, 'DONE'),
                       (2, 'URL', NULL, 'https://example.com/b', 'b', 200, 200, 'DONE'),
                       (3, 'URL', '  ', 'https://example.com/c', 'c', 300, 300, 'DONE'),
                       (4, 'TEXT', 'メモ', NULL, 'd', 400, 400, 'DONE')
                """.trimIndent()
            )
            close()
        }

        // スキーマ v11 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11))

        val fetchedAt = connection.prepare("SELECT contentFetchedAt FROM bookmarks ORDER BY id").use { stmt ->
            buildList { while (stmt.step()) add(if (stmt.isNull(0)) null else stmt.getLong(0)) }
        }
        assertEquals(listOf(100L, null, null, null), fetchedAt)
        connection.close()
    }

    @Test
    fun migrate13To14_addsSummaryCoveredUntilAsEmpty() {
        helper.createDatabase(13).apply {
            execSQL(
                """
                INSERT INTO conversations (id, title, isTitleManuallySet, createdAt, updatedAt)
                VALUES (1, '会話', 0, 100, 200)
                """.trimIndent()
            )
            close()
        }

        // スキーマ v14 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(14, listOf(MIGRATION_13_14))

        val row = connection.prepare("SELECT title, summary, summaryCoveredUntil FROM conversations").use { stmt ->
            stmt.step()
            Triple(stmt.getText(0), stmt.isNull(1), stmt.isNull(2))
        }
        // 既存の会話に要約はまだ無い(次の発言の後に作り始める)
        assertEquals(Triple("会話", true, true), row)
        connection.close()
    }

    @Test
    fun migrate11To12_processingBecomesPendingAndPendingGetsQueuedAt() {
        helper.createDatabase(11).apply {
            execSQL(
                """
                INSERT INTO bookmarks (id, type, content, contentUri, title, createdAt, lastAccessedAt, aiStatus)
                VALUES (1, 'TEXT', 'a', NULL, 'a', 100, 100, 'PROCESSING'),
                       (2, 'TEXT', 'b', NULL, 'b', 200, 200, 'PENDING'),
                       (3, 'TEXT', 'c', NULL, 'c', 300, 300, 'DONE'),
                       (4, 'TEXT', 'd', NULL, 'd', 400, 400, 'FAILED'),
                       (5, 'TEXT', 'e', NULL, 'e', 500, 500, 'WAITING_MODEL')
                """.trimIndent()
            )
            close()
        }

        // スキーマ v12 と一致するか(列・既定値・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(12, listOf(MIGRATION_11_12))

        val rows = connection.prepare(
            "SELECT aiStatus, queuedAt, processingStage, processingStartedAt, stageStartedAt, processingFinishedAt, " +
                "failureReason FROM bookmarks ORDER BY id"
        ).use { stmt ->
            buildList {
                while (stmt.step()) {
                    add(stmt.getText(0) to (1..6).map { if (stmt.isNull(it)) null else stmt.getText(it) })
                }
            }
        }
        val empty = List(5) { null }
        assertEquals(
            listOf(
                // 処理中だったものは、実行しているワークが無いので処理待ちにする。頼んだ日時は保存日時で埋める
                "PENDING" to listOf("100") + empty,
                "PENDING" to listOf("200") + empty,
                // 終えたものの日時・理由は分からないので空のまま
                "DONE" to listOf(null) + empty,
                "FAILED" to listOf(null) + empty,
                "WAITING_MODEL" to listOf(null) + empty
            ),
            rows
        )
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

    @Test
    fun migrate12To13_addsEmptyApiUsageTableAndKeepsBookmarks() {
        helper.createDatabase(12).apply {
            execSQL(
                """
                INSERT INTO bookmarks (id, type, content, contentUri, title, createdAt, lastAccessedAt, aiStatus)
                VALUES (1, 'TEXT', 'a', NULL, '既存のタイトル', 100, 100, 'DONE')
                """.trimIndent()
            )
            close()
        }

        // スキーマ v13 と一致するか(テーブル・列・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(13, listOf(MIGRATION_12_13))

        // 既存の行はそのまま、使用量の記録は空から始まる
        val titles = connection.prepare("SELECT title FROM bookmarks").use { stmt ->
            buildList { while (stmt.step()) add(stmt.getText(0)) }
        }
        assertEquals(listOf("既存のタイトル"), titles)
        val usageRows = connection.prepare("SELECT COUNT(*) FROM api_usage").use { stmt ->
            stmt.step()
            stmt.getLong(0)
        }
        assertEquals(0L, usageRows)
        // 新しいテーブルに書ける
        connection.execSQL(
            "INSERT INTO api_usage (createdAt, provider, model, task, inputTokens, outputTokens) " +
                "VALUES (1, 'CLAUDE', 'claude-haiku-5-5', 'SUMMARY', 100, 20)"
        )
        connection.close()
    }


    @Test
    fun migrate14To15_existingSummariesBecomeAiVersionsAndNothingIsMarkedEdited() {
        helper.createDatabase(14).apply {
            execSQL(
                """
                INSERT INTO bookmarks (id, type, content, contentUri, title, summary, createdAt, lastAccessedAt, aiStatus, processingFinishedAt)
                VALUES (1, 'TEXT', 'a', NULL, '要約あり', 'AI の要約', 100, 100, 'DONE', 500),
                       (2, 'TEXT', 'b', NULL, '完了時刻なし', '古い要約', 200, 200, 'DONE', NULL),
                       (3, 'TEXT', 'c', NULL, '要約なし', NULL, 300, 300, 'PENDING', NULL),
                       (4, 'TEXT', 'd', NULL, '空白だけ', '   ', 400, 400, 'DONE', 600)
                """.trimIndent()
            )
            close()
        }

        // スキーマ v15 と一致するか(列・既定値・外部キー・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(15, listOf(MIGRATION_14_15))

        // 手で直した印は、既存の行ではすべて 0
        val edited = connection.prepare("SELECT summaryEdited FROM bookmarks ORDER BY id").use { stmt ->
            buildList { while (stmt.step()) add(stmt.getLong(0)) }
        }
        assertEquals(listOf(0L, 0L, 0L, 0L), edited)
        // 要約のあるものだけ、AI の版として1版ずつ。作った日時は処理を終えた日時(無ければ保存日時)
        val versions = connection.prepare(
            "SELECT bookmarkId, summary, source, generator, style, createdAt FROM summary_history ORDER BY bookmarkId"
        ).use { stmt ->
            buildList {
                while (stmt.step()) {
                    add(
                        listOf(
                            stmt.getLong(0).toString(), stmt.getText(1), stmt.getText(2),
                            if (stmt.isNull(3)) null else stmt.getText(3), if (stmt.isNull(4)) null else stmt.getText(4),
                            stmt.getLong(5).toString()
                        )
                    )
                }
            }
        }
        assertEquals(
            listOf(
                listOf("1", "AI の要約", "AI", null, null, "500"),
                listOf("2", "古い要約", "AI", null, null, "200")
            ),
            versions
        )
        // ブックマークを消すと履歴も消える(外部キーの連鎖)
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("DELETE FROM bookmarks WHERE id = 1")
        val remaining = connection.prepare("SELECT COUNT(*) FROM summary_history").use { stmt ->
            stmt.step()
            stmt.getLong(0)
        }
        assertEquals(1L, remaining)
        connection.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }


    @Test
    fun migrate15To16_existingTagsStayTopLevelAndParentCanBeSetAndCleared() {
        helper.createDatabase(15).apply {
            execSQL("INSERT INTO tags (id, name, isUserCreated) VALUES (1, 'kotlin', 1), (2, 'android', 0)")
            close()
        }

        // スキーマ v16 と一致するか(列・外部キー・インデックス)も検証される
        val connection = helper.runMigrationsAndValidate(16, listOf(MIGRATION_15_16))

        // 既存のタグはすべて最上位で、ほかの列は変わらない
        val rows = connection.prepare("SELECT id, name, isUserCreated, parentId FROM tags ORDER BY id").use { stmt ->
            buildList {
                while (stmt.step()) {
                    add(listOf(stmt.getLong(0).toString(), stmt.getText(1), stmt.getLong(2).toString(), stmt.isNull(3).toString()))
                }
            }
        }
        assertEquals(listOf(listOf("1", "kotlin", "1", "true"), listOf("2", "android", "0", "true")), rows)

        // 親を設定でき、親を消すと子は最上位に戻る(外部キーの SET NULL)
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("UPDATE tags SET parentId = 1 WHERE id = 2")
        connection.execSQL("DELETE FROM tags WHERE id = 1")
        val parentAfter = connection.prepare("SELECT parentId FROM tags WHERE id = 2").use { stmt ->
            stmt.step()
            stmt.isNull(0)
        }
        assertTrue(parentAfter)
        connection.close()
    }
}
