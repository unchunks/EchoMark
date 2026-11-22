package com.unchunks.echomark.data.local.encryption

import com.unchunks.components.securestorage.SecretCipher
import com.unchunks.components.securestorage.UnrecoverableSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * DB の暗号化の手順(平文の DB の暗号化・途中で止まったときの片付け・鍵が失われたとき)。
 * SQLCipher のネイティブのライブラリは JVM で動かないため、ファイルの形を真似た Fake の仕組みで確かめる
 * (本物の SQLCipher での暗号化は、端末のテスト DatabaseEncryptionInstrumentedTest で確かめる)。
 */
class DatabaseEncryptionMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 平文の DB は SQLite の印 + 「テーブル=行の数」の行、暗号化した DB は「ENC」+ パスフレーズ + 同じ行 */
    private class FakeEngine : EncryptedDatabaseEngine {
        var failExport = false
        var corruptExport = false
        var failCheckAfterSwap = false
        var transientCheckError = false

        override fun export(plain: File, encrypted: File, passphrase: ByteArray) {
            if (failExport) throw IllegalStateException("export failed")
            val body = plainBody(plain)
            encrypted.writeText("ENC\n${String(passphrase)}\n" + if (corruptExport) "bookmarks=0\n" else body)
        }

        override fun summary(file: File, passphrase: ByteArray?): DatabaseSummary {
            val body = if (passphrase == null) plainBody(file) else encryptedBody(file, passphrase) ?: error("cannot open")
            val counts = body.lines().filter { it.isNotBlank() }.associate { line -> line.substringBefore('=') to line.substringAfter('=').toLong() }
            return DatabaseSummary(1, counts)
        }

        override fun check(file: File, passphrase: ByteArray): OpenCheck {
            if (transientCheckError) throw IOException("busy")
            if (failCheckAfterSwap) return OpenCheck.NOT_READABLE
            return if (encryptedBody(file, passphrase) != null) OpenCheck.OPENED else OpenCheck.NOT_READABLE
        }

        private fun plainBody(file: File): String = file.readText().removePrefix(PLAIN_HEADER)

        private fun encryptedBody(file: File, passphrase: ByteArray): String? {
            val lines = file.readText().split("\n", limit = 3)
            return if (lines.size == 3 && lines[0] == "ENC" && lines[1] == String(passphrase)) lines[2] else null
        }
    }

    /** 暗号化の形を真似た Fake の Keystore。[lost] で鍵が失われた、[transient] で一時的なエラー */
    private class FakeCipher : SecretCipher {
        var lost = false
        var transient = false
        override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(0x42) + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun decrypt(data: ByteArray): ByteArray {
            if (transient) throw GeneralSecurityException("keystore busy")
            if (lost || data.firstOrNull() != 0x42.toByte()) throw UnrecoverableSecretException("key not found")
            return data.drop(1).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private val dbDir by lazy { temp.newFolder("databases") }
    private val main by lazy { File(dbDir, "echomark.db") }
    private val backup by lazy { File(dbDir, "echomark.db.plain-bak") }
    private val target by lazy { File(dbDir, "echomark-encrypted.db") }
    private val keyFile by lazy { File(temp.newFolder("no_backup"), "database_key.bin") }
    private val engine = FakeEngine()
    private val cipher = FakeCipher()
    private val keyStore by lazy { DatabaseKeyStore(keyFile, cipher) }
    private val migrator by lazy { DatabaseEncryptionMigrator(main, engine, keyStore) }

    private fun writePlain(body: String = "bookmarks=3\ntags=2\n") {
        main.writeText(PLAIN_HEADER + body)
        File(main.path + "-wal").writeText("wal")
        File(main.path + "-shm").writeText("shm")
    }

    private fun leftovers() = dbDir.listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun 初めて入れたときは鍵を作り_新しく作るDBを暗号化する() {
        val outcome = migrator.prepare()

        assertTrue(outcome is EncryptionOutcome.Encrypted)
        assertFalse((outcome as EncryptionOutcome.Encrypted).migrated)
        assertTrue(keyFile.isFile)
        // 鍵のファイルにパスフレーズをそのまま書かない
        assertFalse(String(keyFile.readBytes()).contains(String(outcome.passphrase)))
        assertEquals(64, outcome.passphrase.size)
    }

    @Test
    fun 平文のDBを暗号化し_確かめてから平文の写しを残さず消す() {
        writePlain()

        val outcome = migrator.prepare() as EncryptionOutcome.Encrypted

        assertTrue(outcome.migrated)
        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        assertEquals(mapOf("bookmarks" to 3L, "tags" to 2L), engine.summary(main, outcome.passphrase).rowCounts)
        // 平文の写し・写している途中のもの・平文の WAL と SHM は残らない
        assertEquals(setOf("echomark.db"), leftovers())

        // 次に開くときは、そのまま暗号化した DB を開く
        val again = DatabaseEncryptionMigrator(main, engine, keyStore).prepare() as EncryptionOutcome.Encrypted
        assertFalse(again.migrated)
        assertArrayEquals(outcome.passphrase, again.passphrase)
    }

    @Test
    fun 写せなければ平文のDBのまま開き_次の起動でやり直す() {
        writePlain()
        val before = main.readText()
        engine.failExport = true

        val outcome = migrator.prepare()

        assertTrue(outcome is EncryptionOutcome.PlaintextFallback)
        assertEquals(before, main.readText())
        assertFalse(target.exists())
        assertFalse(backup.exists())

        engine.failExport = false
        assertTrue((DatabaseEncryptionMigrator(main, engine, keyStore).prepare() as EncryptionOutcome.Encrypted).migrated)
    }

    @Test
    fun 写したものの中身が元と合わなければ平文のまま() {
        writePlain()
        engine.corruptExport = true

        assertTrue(migrator.prepare() is EncryptionOutcome.PlaintextFallback)
        assertTrue(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        assertFalse(target.exists())
    }

    @Test
    fun 入れ替えたあとに開けなければ平文に戻す() {
        writePlain()
        val before = main.readText()
        engine.failCheckAfterSwap = true

        assertTrue(migrator.prepare() is EncryptionOutcome.PlaintextFallback)

        assertEquals(before, main.readText())
        assertFalse(backup.exists())
        assertFalse(target.exists())
    }

    @Test
    fun 前の起動で入れ替えたあとに止まっていたら_開けることを確かめて平文の写しを消す() {
        writePlain()
        val passphrase = (DatabaseEncryptionMigrator(main, engine, keyStore).prepare() as EncryptionOutcome.Encrypted).passphrase
        // 平文の写しを消す前に止まった
        backup.writeText(PLAIN_HEADER + "bookmarks=3\n")

        val outcome = DatabaseEncryptionMigrator(main, engine, keyStore).prepare() as EncryptionOutcome.Encrypted

        assertArrayEquals(passphrase, outcome.passphrase)
        assertFalse(backup.exists())
        assertEquals(setOf("echomark.db"), leftovers())
    }

    @Test
    fun 前の起動で入れ替えたあとの暗号化したDBを開けなければ_平文の写しから暗号化し直す() {
        main.writeText("ENC\nwrong-key\nbookmarks=3\n")
        backup.writeText(PLAIN_HEADER + "bookmarks=3\ntags=1\n")

        val outcome = migrator.prepare() as EncryptionOutcome.Encrypted

        assertTrue(outcome.migrated)
        assertEquals(mapOf("bookmarks" to 3L, "tags" to 1L), engine.summary(main, outcome.passphrase).rowCounts)
        assertFalse(backup.exists())
    }

    @Test
    fun 鍵が失われて暗号化したDBを開けなければ_消して新しい鍵で作り直し_平文を残さない() {
        writePlain()
        val old = (DatabaseEncryptionMigrator(main, engine, keyStore).prepare() as EncryptionOutcome.Encrypted).passphrase
        cipher.lost = true

        val outcome = DatabaseEncryptionMigrator(main, engine, keyStore).prepare()

        assertTrue(outcome is EncryptionOutcome.KeyLost)
        assertFalse(main.exists())
        assertTrue(leftovers().isEmpty())
        cipher.lost = false
        assertFalse((outcome as EncryptionOutcome.KeyLost).passphrase.contentEquals(old))
        // 新しい鍵は保存されていて、次はそれで開く
        assertArrayEquals(outcome.passphrase, keyStore.loadOrCreate().passphrase)
    }

    @Test
    fun 鍵が失われても平文のDBなら_新しい鍵で暗号化して失わない() {
        keyStore.loadOrCreate()
        writePlain()
        cipher.lost = true

        val outcome = migrator.prepare()

        cipher.lost = false
        assertTrue((outcome as EncryptionOutcome.Encrypted).migrated)
        assertEquals(mapOf("bookmarks" to 3L, "tags" to 2L), engine.summary(main, outcome.passphrase).rowCounts)
    }

    @Test
    fun Keystoreの一時的なエラーや開けない理由が分からないときはDBを消さずに投げる() {
        writePlain()
        DatabaseEncryptionMigrator(main, engine, keyStore).prepare()
        val encrypted = main.readText()

        cipher.transient = true
        assertThrows(GeneralSecurityException::class.java) { DatabaseEncryptionMigrator(main, engine, keyStore).prepare() }
        cipher.transient = false
        engine.transientCheckError = true
        assertThrows(IOException::class.java) { DatabaseEncryptionMigrator(main, engine, keyStore).prepare() }

        assertEquals(encrypted, main.readText())
    }

    @Test
    fun 全データ削除のときは平文の写しと写している途中のものを消す() {
        backup.writeText(PLAIN_HEADER + "bookmarks=3\n")
        File(backup.path + "-wal").writeText("wal")
        target.writeText("ENC\nx\n")

        migrator.deleteLeftovers()

        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun 平文のSQLiteの印を見分ける() {
        main.writeText(PLAIN_HEADER + "x")
        assertTrue(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        main.writeText("ENC\n")
        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(main))
        assertFalse(DatabaseEncryptionMigrator.isPlaintextSqlite(File(dbDir, "missing.db")))
    }

    @Test
    fun 鍵はランダムで_文字列にしても中身を出さない() {
        val first = keyStore.loadOrCreate()
        assertTrue(first is DatabaseKey.Created)
        assertTrue(String(first.passphrase).matches(Regex("[0-9a-f]{64}")))
        val second = keyStore.loadOrCreate()
        assertTrue(second is DatabaseKey.Existing)
        assertArrayEquals(first.passphrase, second.passphrase)
        assertFalse(keyStore.toString().contains(String(first.passphrase)))
        assertNotEquals(String(first.passphrase), String(DatabaseKeyStore(File(temp.root, "other.bin"), cipher).loadOrCreate().passphrase))
    }

    private companion object {
        const val PLAIN_HEADER = "SQLite format 3\u0000"
    }
}
