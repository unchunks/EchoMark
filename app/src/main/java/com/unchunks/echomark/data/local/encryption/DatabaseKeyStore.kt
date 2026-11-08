package com.unchunks.echomark.data.local.encryption

import com.unchunks.components.securestorage.SecretCipher
import com.unchunks.components.securestorage.UnrecoverableSecretException
import java.io.File
import java.security.SecureRandom

/** DB の鍵(パスフレーズ)を読んだ結果 */
sealed interface DatabaseKey {
    val passphrase: ByteArray

    /** 前から使っている鍵 */
    class Existing(override val passphrase: ByteArray) : DatabaseKey

    /** 初めて作った鍵(まだ DB を暗号化していない) */
    class Created(override val passphrase: ByteArray) : DatabaseKey

    /** 鍵を読めなくなった(Keystore の鍵が失われた・壊れた)。暗号化した DB はもう開けない */
    data object Lost : DatabaseKey {
        override val passphrase: ByteArray get() = throw IllegalStateException("key lost")
    }
}

/**
 * DB を暗号化する鍵(パスフレーズ)を持つ。ランダムな 32 バイトを 16 進の文字にしたものをパスフレーズにし、
 * Android Keystore の鍵([cipher])で暗号化して [file](バックアップされない場所)に置く。
 *
 * - パスフレーズはログに出さない(この型は toString でも中身を出さない)
 * - 復号できない(鍵が失われた・壊れた)ときは [DatabaseKey.Lost]。Keystore の一時的なエラーは、そのまま投げる
 *   (DB を消してしまわないよう、次の起動でやり直す)
 * - 利用者の認証(生体認証など)には結び付けない(裏で動く処理も DB を使うため。画面ロックを変えても鍵が失われないように)
 */
class DatabaseKeyStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val random: SecureRandom = SecureRandom()
) {

    fun loadOrCreate(): DatabaseKey {
        if (!file.isFile) return DatabaseKey.Created(create())
        return try {
            DatabaseKey.Existing(cipher.decrypt(file.readBytes()))
        } catch (e: UnrecoverableSecretException) {
            DatabaseKey.Lost
        }
    }

    /** 新しい鍵に置き換える(鍵が失われたとき・平文の DB を暗号化し直すとき) */
    fun reset(): ByteArray = create()

    private fun create(): ByteArray {
        val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
        val passphrase = raw.toHex().toByteArray(Charsets.US_ASCII)
        raw.fill(0)
        file.parentFile?.mkdirs()
        // 途中で止まっても、壊れた鍵のファイルを残さない
        val part = File(file.path + ".part")
        part.writeBytes(cipher.encrypt(passphrase))
        if (!part.renameTo(file)) {
            file.delete()
            if (!part.renameTo(file)) throw java.io.IOException("鍵のファイルを書けない")
        }
        return passphrase
    }

    override fun toString(): String = "DatabaseKeyStore(${file.name})"

    private companion object {
        const val KEY_BYTES = 32

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
