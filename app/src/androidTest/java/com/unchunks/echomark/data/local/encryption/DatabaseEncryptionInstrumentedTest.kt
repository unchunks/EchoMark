package com.unchunks.echomark.data.local.encryption

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unchunks.components.securestorage.KeystoreSecretCipher
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * 本物の SQLCipher と Android Keystore で、DB の暗号化を確かめる(JVM のテストでは Fake の仕組みでしか確かめられない部分)。
 * - 平文の Room の DB を暗号化し、暗号化した DB を Room で開いて中身を読める
 * - 平文の写し・写している途中のもの・平文の WAL が残らない。ファイルに平文の中身が見えない。違う鍵では読めない
 * - Keystore の鍵が失われたら、暗号化した DB を消して作り直し、平文を残さない
 *
 * 実行: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unchunks.echomark.data.local.encryption.DatabaseEncryptionInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main: File = context.getDatabasePath(DB_NAME)
    private val keyFile = File(context.noBackupFilesDir, "encryption-test-key.bin")

    private fun migrator() = DatabaseEncryptionMigrator(main, SqlCipherEngine(), DatabaseKeyStore(keyFile, KeystoreSecretCipher(KEY_ALIAS)))

    @Before
    fun setUp() = cleanUp()

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        main.parentFile?.listFiles()?.filter { it.name.startsWith(DB_PREFIX) }?.forEach { it.delete() }
        keyFile.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun bookmark(title: String) = BookmarkEntity(
        type = BookmarkType.TEXT, title = title, content = "本文 $title", createdAt = 1, lastAccessedAt = 1
    )

    private fun encryptedRoom(migrator: DatabaseEncryptionMigrator, onOutcome: (EncryptionOutcome) -> Unit = {}) =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .openHelperFactory(EncryptingOpenHelperFactory(migrator::prepare, onOutcome))
            .build()

    @Test
    fun 平文のRoomのDBを本物のSQLCipherで暗号化し_Roomで開いて読める() {
        val plain = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
        runBlocking {
            plain.bookmarkDao().insert(bookmark(SECRET_TITLE))
            plain.bookmarkDao().insert(bookmark("二つ目"))
        }
        plain.close()
        assertTrue(DatabaseEncryptionMigrator.isPlaintextSqlite(main))

        var outcome: EncryptionOutcome? = null
        val encrypted = encryptedRoom(migrator()) { outcome = it }
        val titles = runBlocking { encrypted.backupDao().getAllBookmarks().map { it.title } }
        encrypted.close()

        assertTrue(outcome.toString(), outcome is EncryptionOutcome.Encrypted)
        assertTrue((outcome as EncryptionOutcome.Encrypted).migrated)
        assertEquals(listOf(SECRET_TITLE, "二つ目"), titles)
        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        // 平文の写し・写している途中のものは残らない
        val names = main.parentFile!!.listFiles()!!.map { it.name }.filter { it.startsWith(DB_PREFIX) }
        assertFalse(names.toString(), names.any { it.contains("plain-bak") || it.contains("-encrypted") })
        // ファイルの中に平文の中身が見えない
        val bytes = main.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(SECRET_TITLE.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
        // 違う鍵では読めない
        assertEquals(OpenCheck.NOT_READABLE, SqlCipherEngine().check(main, "wrong".toByteArray()))

        // 次に開くときは、暗号化し直さずに開く
        var again: EncryptionOutcome? = null
        val reopened = encryptedRoom(migrator()) { again = it }
        assertEquals(2, runBlocking { reopened.backupDao().getAllBookmarks().size })
        reopened.close()
        assertTrue(again.toString(), again is EncryptionOutcome.Encrypted)
        assertFalse((again as EncryptionOutcome.Encrypted).migrated)
    }

    @Test
    fun 新しく作るDBも暗号化する() {
        val db = encryptedRoom(migrator())
        runBlocking { db.bookmarkDao().insert(bookmark(SECRET_TITLE)) }
        db.close()

        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
    }

    @Test
    fun Keystoreの鍵が失われたら_読めないDBを消して作り直し_平文を残さない() {
        val first = encryptedRoom(migrator())
        runBlocking { first.bookmarkDao().insert(bookmark(SECRET_TITLE)) }
        first.close()
        // 端末の初期化などで Keystore の鍵が失われた
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)

        var outcome: EncryptionOutcome? = null
        val second = encryptedRoom(migrator()) { outcome = it }
        val count = runBlocking { second.backupDao().getAllBookmarks().size }
        second.close()

        assertTrue(outcome is EncryptionOutcome.KeyLost)
        assertEquals(0, count)
        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        val names = main.parentFile!!.listFiles()!!.map { it.name }.filter { it.startsWith(DB_PREFIX) }
        assertFalse(names.toString(), names.any { it.contains("plain-bak") })
    }

    private companion object {
        const val DB_PREFIX = "encryption-test"
        const val DB_NAME = "$DB_PREFIX.db"
        const val KEY_ALIAS = "echomark_encryption_test_key"
        const val SECRET_TITLE = "とても秘密のブックマーク"
    }
}
