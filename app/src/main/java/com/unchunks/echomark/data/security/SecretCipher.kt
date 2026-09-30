package com.unchunks.echomark.data.security

/**
 * 秘密情報(API キー)の暗号化・復号。
 * 本番は Android Keystore の AES/GCM 鍵を使う [KeystoreSecretCipher]。JVM テストではフェイクに差し替える。
 */
interface SecretCipher {
    /** 平文を暗号化する。戻り値は IV などの復号に必要な情報を含む。 */
    fun encrypt(plain: ByteArray): ByteArray

    /**
     * [encrypt] の出力を復号する。
     * @throws java.security.GeneralSecurityException 鍵が失われた・改ざんされたなどで復号できないとき
     */
    fun decrypt(data: ByteArray): ByteArray
}
