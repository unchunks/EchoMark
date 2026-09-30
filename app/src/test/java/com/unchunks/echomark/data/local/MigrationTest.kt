package com.unchunks.echomark.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.echomark.domain.bookmark.model.AiStatus
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
