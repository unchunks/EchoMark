package com.unchunks.echomark.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore に置いた AES-256/GCM 鍵で暗号化する。鍵は端末外へ取り出せない。
 * 出力形式: [IV の長さ(1 byte)][IV][暗号文 + 認証タグ]
 */
@Singleton
class KeystoreSecretCipher @Inject constructor() : SecretCipher {

    private val lock = Any()

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // IV は Keystore が毎回ランダムに生成する(呼び出し側で指定すると拒否される)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    override fun decrypt(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw GeneralSecurityException("empty payload")
        val ivLength = data[0].toInt()
        if (ivLength <= 0 || data.size <= 1 + ivLength) throw GeneralSecurityException("invalid payload")
        val iv = data.copyOfRange(1, 1 + ivLength)
        val encrypted = data.copyOfRange(1 + ivLength, data.size)
        val key = loadKey() ?: throw GeneralSecurityException("key not found")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        loadKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "echomark_api_key_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
