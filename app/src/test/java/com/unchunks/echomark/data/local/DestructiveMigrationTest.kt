package com.unchunks.echomark.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.echomark.di.applyMigrationPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 破壊的マイグレーション(開発初期の版からの更新)で DB を作り直したときに、
 * ObjectBox の埋め込みも消す(ID の再利用で古いベクトルが新しいブックマークに紐付かないようにする)。
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

    private fun openWithAppPolicy(onDestructive: () -> Unit): AppDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, TEST_DB)
            .applyMigrationPolicy(onDestructive)
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
        helper.createDatabase(6).close()
        var destructiveCalls = 0

        val room = openWithAppPolicy { destructiveCalls++ }
        try {
            room.bookmarkDao().getAllIds()
        } finally {
            room.close()
        }
        assertEquals(0, destructiveCalls)
    }

    private companion object {
        const val TEST_DB = "destructive-migration-test.db"
    }
}
