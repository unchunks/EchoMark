package com.unchunks.echomark.data.local.encryption

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/** 暗号化した DB を読み書きする仕組み(本番は SQLCipher の [SqlCipherEngine]。JVM のテストでは Fake) */
interface EncryptedDatabaseEngine {
    /**
     * 平文の DB [plain] の中身を、[passphrase] で暗号化した [encrypted] に写す(WAL を書き戻してから。DB の版も写す)。
     * [plain] は変えない
     */
    fun export(plain: File, encrypted: File, passphrase: ByteArray)

    /** テーブルごとの行の数と DB の版(平文なら [passphrase] は null)。写せたかを比べるのに使う */
    fun summary(file: File, passphrase: ByteArray?): DatabaseSummary

    /**
     * [passphrase] で開けて、中身を読めるか。鍵が違う・DB でないときは [OpenCheck.NOT_READABLE]。
     * それ以外の失敗(読み書きできない・ほかが使っているなど)は投げる(DB を消してしまわないように)
     */
    fun check(file: File, passphrase: ByteArray): OpenCheck
}

enum class OpenCheck { OPENED, NOT_READABLE }

/** DB の中身の要約(テーブル名 → 行の数と、DB の版) */
data class DatabaseSummary(val version: Int, val rowCounts: Map<String, Long>)

/** 暗号化の途中で残ったもの(平文の写し)を消す。全データ削除から呼ぶ(テストでは Fake) */
interface DatabaseLeftovers {
    fun deleteLeftovers()
}

/** 起動したときの暗号化の結果 */
sealed interface EncryptionOutcome {
    /** 暗号化した DB を [passphrase] で開く(新しく作る DB も暗号化する) */
    class Encrypted(val passphrase: ByteArray, val migrated: Boolean = false) : EncryptionOutcome

    /** 暗号化に失敗したので、今回は平文の DB のまま開く(次の起動でやり直す) */
    data class PlaintextFallback(val reason: String) : EncryptionOutcome

    /** 鍵が失われ、暗号化した DB を開けなかった。DB を消して新しい鍵で作り直す(バックアップから戻してもらう) */
    class KeyLost(val passphrase: ByteArray) : EncryptionOutcome
}

/**
 * Room が DB を開く前に、DB を暗号化した状態にそろえる(#100)。Room の DB を最初に開くとき(裏のスレッド)に1回だけ呼ぶ。
 *
 * ファイル(どれも DB のディレクトリの中):
 * - [main]: Room が開く DB(`echomark.db`)。暗号化していれば先頭が SQLite の印でない
 * - [target]: 暗号化して写している途中のもの(`echomark-encrypted.db`)。途中で止まったら次の起動で消す
 * - [backup]: 暗号化する前の平文の DB(`echomark.db.plain-bak`)。暗号化した DB を開けたことを確かめたら、すぐに消す(上書きしてから)
 *
 * 平文の DB の暗号化: WAL を書き戻して写す → 写したもののテーブルごとの行の数と版を比べる → 名前を入れ替える →
 * 暗号化した DB を開けたら平文を消す。途中で失敗したら、平文の DB をそのまま使い(今回は平文で開く)、次の起動でやり直す。
 * 入れ替えのあと開けなければ、平文に戻す。データを失う経路は、鍵が失われて暗号化した DB を開けないときだけ。
 */
class DatabaseEncryptionMigrator(
    private val main: File,
    private val engine: EncryptedDatabaseEngine,
    private val keyStore: DatabaseKeyStore
) : DatabaseLeftovers {
    private val target = File(main.parentFile, main.nameWithoutExtension + "-encrypted.db")
    private val backup = File(main.path + ".plain-bak")

    fun prepare(): EncryptionOutcome {
        var key = keyStore.loadOrCreate()
        // 写している途中で止まったもの(暗号化してあるので、ふつうに消す)
        deleteWithSidecars(target, secure = false)
        recoverInterruptedSwap(key)

        if (!main.isFile) {
            // 新しく作る DB(初めて入れたとき・全部消したあと)。鍵が失われていても、失うデータは無い
            if (key is DatabaseKey.Lost) key = DatabaseKey.Created(keyStore.reset())
            return EncryptionOutcome.Encrypted(key.passphrase)
        }
        if (isPlaintextSqlite(main)) {
            // 平文の DB の鍵が失われても、平文なので失うデータは無い。新しい鍵で暗号化する
            if (key is DatabaseKey.Lost) key = DatabaseKey.Created(keyStore.reset())
            return encrypt(key.passphrase)
        }
        if (key !is DatabaseKey.Lost && engine.check(main, key.passphrase) == OpenCheck.OPENED) return EncryptionOutcome.Encrypted(key.passphrase)

        // 暗号化した DB を、鍵が失われた(または壊れた)ため開けない。もう読めないので消して作り直す(平文の写しは作らない)
        Timber.w("DB の鍵が使えないため、暗号化した DB を作り直す")
        deleteWithSidecars(main, secure = false)
        return EncryptionOutcome.KeyLost(keyStore.reset())
    }

    /**
     * 前の起動で、名前を入れ替えたあとに止まった([backup] が残っている)。
     * 暗号化した DB を開ければ平文を消し、開けなければ平文に戻す(このあと、もう一度暗号化する)
     */
    private fun recoverInterruptedSwap(key: DatabaseKey) {
        if (!backup.isFile) return
        val encryptedIsUsable = main.isFile && !isPlaintextSqlite(main) && key !is DatabaseKey.Lost && engine.check(main, key.passphrase) == OpenCheck.OPENED
        if (encryptedIsUsable) {
            deleteWithSidecars(backup, secure = true)
        } else {
            deleteWithSidecars(main, secure = isPlaintextSqlite(main))
            if (!backup.renameTo(main)) throw java.io.IOException("平文の DB に戻せない")
        }
    }

    private fun encrypt(passphrase: ByteArray): EncryptionOutcome {
        try {
            engine.export(main, target, passphrase)
            val before = engine.summary(main, null)
            val after = engine.summary(target, passphrase)
            if (before != after) {
                deleteWithSidecars(target, secure = false)
                return EncryptionOutcome.PlaintextFallback("写した DB の中身が元と合わない")
            }
        } catch (e: Exception) {
            Timber.w("DB を暗号化できない(%s)", e.javaClass.simpleName)
            deleteWithSidecars(target, secure = false)
            return EncryptionOutcome.PlaintextFallback("DB を暗号化できない(${e.javaClass.simpleName})")
        }

        // 入れ替える。平文の WAL・SHM は書き戻し済み(写すときに書き戻している)なので、上書きして消す
        if (!main.renameTo(backup)) {
            deleteWithSidecars(target, secure = false)
            return EncryptionOutcome.PlaintextFallback("DB の名前を変えられない")
        }
        sidecarsOf(main).forEach { secureDelete(it) }
        if (!target.renameTo(main)) {
            backup.renameTo(main)
            deleteWithSidecars(target, secure = false)
            return EncryptionOutcome.PlaintextFallback("暗号化した DB の名前を変えられない")
        }
        val opened = try {
            engine.check(main, passphrase) == OpenCheck.OPENED
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            // 開けなければ平文に戻す
            deleteWithSidecars(main, secure = false)
            if (!backup.renameTo(main)) throw java.io.IOException("平文の DB に戻せない")
            return EncryptionOutcome.PlaintextFallback("暗号化した DB を開けない")
        }
        // 開けたので、平文の写しはすぐに消す(残さない)
        deleteWithSidecars(backup, secure = true)
        Timber.i("DB を暗号化した")
        return EncryptionOutcome.Encrypted(passphrase, migrated = true)
    }

    /** 平文の写しが残っていれば消す(全データ削除のときに、念のため) */
    override fun deleteLeftovers() {
        deleteWithSidecars(backup, secure = true)
        deleteWithSidecars(target, secure = false)
    }

    private fun deleteWithSidecars(file: File, secure: Boolean) {
        (listOf(file) + sidecarsOf(file)).forEach { if (secure) secureDelete(it) else it.delete() }
    }

    private fun sidecarsOf(file: File): List<File> = SIDECAR_SUFFIXES.map { File(file.path + it) }

    companion object {
        private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** 先頭が平文の SQLite の印か(暗号化した DB は先頭も暗号化されている) */
        fun isPlaintextSqlite(file: File): Boolean {
            if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
            val header = ByteArray(SQLITE_HEADER.size)
            file.inputStream().use { input -> if (input.read(header) != header.size) return false }
            return header.contentEquals(SQLITE_HEADER)
        }

        /**
         * 中身を 0 で上書きしてから消す。フラッシュのストレージでは、書いた場所が元の場所と同じとは限らない(できる範囲の対策)。
         * そのため、平文の写しを作る時間はできるだけ短くし、端末の暗号化(FBE)と合わせて守る
         */
        fun secureDelete(file: File) {
            if (!file.isFile) return
            try {
                RandomAccessFile(file, "rws").use { raf ->
                    val zeros = ByteArray(64 * 1024)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
            } catch (e: Exception) {
                Timber.w("上書きできない(%s)。消すだけにする", e.javaClass.simpleName)
            }
            if (!file.delete()) Timber.w("消せない: %s", file.name)
        }
    }
}
