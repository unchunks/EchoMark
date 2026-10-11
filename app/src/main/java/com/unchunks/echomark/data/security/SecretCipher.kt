package com.unchunks.echomark.data.security

import java.security.GeneralSecurityException

/**
 * 秘密情報(API キー)の暗号化・復号。
 * 本番は Android Keystore の AES/GCM 鍵を使う [KeystoreSecretCipher]。JVM テストではフェイクに差し替える。
 */
interface SecretCipher {
    /** 平文を暗号化する。戻り値は IV などの復号に必要な情報を含む。 */
    fun encrypt(plain: ByteArray): ByteArray

    /**
     * [encrypt] の出力を復号する。
     * @throws UnrecoverableSecretException 鍵が失われた・データが壊れている・改ざんされたなど、やり直しても復号できないとき
     * @throws java.security.GeneralSecurityException その他の失敗(Keystore の一時的なエラーなど。やり直せば復号できうる)
     */
    fun decrypt(data: ByteArray): ByteArray
}

/** 鍵が失われた・データが壊れている・改ざんされたなど、やり直しても復号できないことを表す。 */
class UnrecoverableSecretException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)
