package com.unchunks.echomark.data.local.encryption

import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import java.io.File

/**
 * SQLCipher で DB を写す・確かめる([EncryptedDatabaseEngine])。ネイティブのライブラリを使うので、JVM のテストでは動かない
 * (端末のテスト `DatabaseEncryptionInstrumentedTest` で確かめる)。
 *
 * パスフレーズは SQL の文字列に埋め込まず、バインドの引数で渡す(ログ・例外の文言に出ないように)。
 */
class SqlCipherEngine : EncryptedDatabaseEngine {

    init {
        loadLibrary()
    }

    override fun export(plain: File, encrypted: File, passphrase: ByteArray) {
        encrypted.delete()
        // ATTACH で新しいファイルを作るため、作ってよい開き方にする(平文の DB 自体は既にあるので作られない)
        open(plain, EMPTY, createAttached = true).use { db ->
            // WAL に残っている書き込みを本体に戻してから写す(写したものと元を比べられるように)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
            val version = db.version
            db.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any>(encrypted.path, String(passphrase, Charsets.US_ASCII)))
            try {
                db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                db.execSQL("PRAGMA encrypted.user_version = $version")
            } finally {
                db.execSQL("DETACH DATABASE encrypted")
            }
        }
    }

    override fun summary(file: File, passphrase: ByteArray?): DatabaseSummary =
        open(file, passphrase ?: EMPTY, readOnly = true).use { db ->
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                emptyArray<String>()
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val counts = tables.associateWith { table ->
                db.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"", emptyArray<String>())
                    .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            }
            DatabaseSummary(db.version, counts)
        }

    override fun check(file: File, passphrase: ByteArray): OpenCheck = try {
        open(file, passphrase, readOnly = true).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
        }
        OpenCheck.OPENED
    } catch (e: SQLiteNotADatabaseException) {
        // 鍵が違う・DB でない
        OpenCheck.NOT_READABLE
    }

    private fun open(file: File, passphrase: ByteArray, readOnly: Boolean = false, createAttached: Boolean = false): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.path,
            passphrase,
            null,
            when {
                readOnly -> SQLiteDatabase.OPEN_READONLY
                createAttached -> SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
                else -> SQLiteDatabase.OPEN_READWRITE
            },
            null,
            null
        )

    companion object {
        private val EMPTY = ByteArray(0)

        @Volatile
        private var loaded = false

        /** SQLCipher を使う前に、ネイティブのライブラリを読み込む(1回だけ) */
        fun loadLibrary() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("sqlcipher")
                    loaded = true
                }
            }
        }
    }
}
