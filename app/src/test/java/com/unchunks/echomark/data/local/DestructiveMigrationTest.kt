package com.unchunks.echomark.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.echomark.di.applyMigrationPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DB を作り直す(破壊的マイグレーションの)経路のテスト。
 * - 作り直すのは開発初期の版(v1〜5)からの更新と、debug ビルドでのダウングレードだけ
 * - 作り直したときは ObjectBox の埋め込みも消す(ID の再利用で古いベクトルが新しいブックマークに紐付かないようにする)
 */
@RunWith(AndroidJUnit4::class)
class DestructiveMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class
    )

    private fun openWithAppPolicy(allowDestructiveDowngrade: Boolean = false, onDestructive: () -> Unit): AppDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, TEST_DB)
            .applyMigrationPolicy(allowDestructiveDowngrade, onDestructive)
            .build()

    @Test
    fun 開発初期の版から作り直したら埋め込みを消す処理を呼ぶ() = runTest {
        helper.createDatabase(4).close()
        var destructiveCalls = 0

        val room = openWithAppPolicy { destructiveCalls++ }
        try {
            // 開いた時点でマイグレーションが走る
            assertTrue(room.bookmarkDao().getAllIds().isEmpty())
        } finally {
            room.close()
        }
        assertEquals(1, destructiveCalls)
    }

    @Test
    fun 正式なマイグレーションでは埋め込みを消さない() = runTest {
        helper.createDatabase(OLDEST_MIGRATABLE_VERSION).close()
        var destructiveCalls = 0

        val room = openWithAppPolicy { destructiveCalls++ }
        try {
            room.bookmarkDao().getAllIds()
        } finally {
            room.close()
        }
        assertEquals(0, destructiveCalls)
    }

    @Test
    fun 作り直すのは公開前の開発初期の版だけ() {
        assertTrue(DESTRUCTIVE_MIGRATION_FROM_VERSIONS.isNotEmpty())
        assertTrue(DESTRUCTIVE_MIGRATION_FROM_VERSIONS.all { it < OLDEST_MIGRATABLE_VERSION })
        assertTrue(OLDEST_MIGRATABLE_VERSION <= FIRST_RELEASED_VERSION)
    }

    @Test
    fun releaseではダウングレードでデータを消さずに開けない() = runTest {
        createNewerDatabaseWithBookmark()
        var destructiveCalls = 0

        val room = openWithAppPolicy(allowDestructiveDowngrade = false) { destructiveCalls++ }
        try {
            assertThrows(IllegalStateException::class.java) {
                room.openHelper.writableDatabase
            }
        } finally {
            room.close()
        }
        assertEquals(0, destructiveCalls)

        // データは残っている(新しいアプリを入れ直せば読める)
        val bookmarks = ApplicationProvider.getApplicationContext<android.content.Context>()
            .openOrCreateDatabase(TEST_DB, 0, null)
            .use { db -> db.rawQuery("SELECT COUNT(*) FROM bookmarks", null).use { it.moveToFirst(); it.getInt(0) } }
        assertEquals(1, bookmarks)
    }

    @Test
    fun debugではダウングレードで作り直して開ける() = runTest {
        createNewerDatabaseWithBookmark()
        var destructiveCalls = 0

        val room = openWithAppPolicy(allowDestructiveDowngrade = true) { destructiveCalls++ }
        try {
            assertTrue(room.bookmarkDao().getAllIds().isEmpty())
        } finally {
            room.close()
        }
        assertEquals(1, destructiveCalls)
    }

    /** 現在より 1 つ新しい版(未来のアプリが作った DB)を模して、版だけ上げた DB を作る。 */
    private fun createNewerDatabaseWithBookmark() {
        helper.createDatabase(AppDatabase.VERSION).apply {
            execSQL(
                """
                INSERT INTO bookmarks (id, type, content, contentUri, title, createdAt, lastAccessedAt, aiStatus)
                VALUES (1, 'URL', NULL, 'https://example.com/a', 'a', 100, 100, 'DONE')
                """.trimIndent()
            )
            execSQL("PRAGMA user_version = ${AppDatabase.VERSION + 1}")
            close()
        }
    }

    private companion object {
        const val TEST_DB = "destructive-migration-test.db"
    }
}
