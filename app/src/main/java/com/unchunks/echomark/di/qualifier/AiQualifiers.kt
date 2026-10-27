package com.unchunks.echomark.di.qualifier

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalAi

/** API キー(暗号文)専用の DataStore。通常の設定とはファイルを分け、バックアップ対象から外す。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiKeyStore

/** 処理の段ごとの所要時間の記録(ProcessingStatsRepository)の DataStore。設定とはファイルを分ける。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcessingStatsStore
