package com.unchunks.echomark.di.qualifier

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalAi

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiAi

/** API キー(暗号文)専用の DataStore。通常の設定とはファイルを分け、バックアップ対象から外す。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiKeyStore
