package com.unchunks.echomark.domain.repository

/** 書き出したバックアップの件数。 */
data class BackupExportSummary(
    val bookmarks: Int,
    val tags: Int,
    val conversations: Int,
    val messages: Int,
    /** 一緒に書き出した添付ファイル・オフラインコピーの数(ファイルも含めて書き出したときだけ) */
    val files: Int = 0
)

/** バックアップを読み込んだ結果。 */
data class BackupImportSummary(
    val bookmarksAdded: Int,
    /** 同じ URL などが既にあって追加しなかったブックマーク */
    val bookmarksSkipped: Int,
    val tagsAdded: Int,
    val conversationsAdded: Int,
    val conversationsSkipped: Int,
    val messagesAdded: Int,
    /** バックアップ(zip)から戻した添付ファイル・オフラインコピーの数 */
    val filesRestored: Int = 0,
    /** 壊れていて読み飛ばしたもの(種類ごと) */
    val skipped: List<BackupSkipped> = emptyList(),
    /** バックアップに入っていた設定(まだ戻していない。利用者が選んだら [DataManagementRepository.restoreSettings] で戻す)。無ければ null */
    val settings: BackupSettings? = null
)

/** 読み飛ばしたもの。[label] は種類(「ノート」など)、[reason] は理由 */
data class BackupSkipped(val label: String, val count: Int, val reason: String)

/**
 * バックアップに入っていた端末の設定(テーマ・並び順・AI の選び方・処理の時間など)。戻すかは利用者が決める。
 * [values] は設定の名前 → 値(戻せるものだけ。API キー・この端末だけの設定・ロックは入らない)
 */
data class BackupSettings(val values: Map<String, Any>) {
    val count: Int get() = values.size
}

/** 端末内で使っている容量(バイト)。 */
data class StorageUsage(
    /** ブックマーク・タグ・会話の DB */
    val databaseBytes: Long,
    /** 検索用の埋め込みベクトル(ObjectBox) */
    val embeddingBytes: Long,
    /** 取り込んだ端末内モデル。未取り込みなら 0 */
    val modelBytes: Long,
    /** 保存した画像・PDF・音声・動画などのファイル */
    val attachmentBytes: Long = 0L
)

/** 画面に出せる文言を持つ、バックアップ・データ操作の失敗。 */
class DataOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * データの持ち出し・読み込み・削除。
 * [uri] は SAF(ファイル選択)で得た content:// の URI 文字列。
 */
interface DataManagementRepository {
    /**
     * ブックマーク・タグ・会話などを JSON で書き出す。API キー・端末内モデル・埋め込みは含めない。
     * [includeFiles] なら、添付ファイル・オフラインコピーの本体も入れた zip にする(何を含めるかは BackupCoverage)。
     */
    suspend fun exportBackup(uri: String, includeFiles: Boolean = false): BackupExportSummary

    /**
     * バックアップ(JSON か zip)を今のデータに統合する。全体を確かめてから書き込み、壊れていれば何も読み込まない
     * (途中で失敗しても今のデータはそのまま)。読み込んだブックマークの埋め込み・AI 処理は裏で再実行する。
     */
    suspend fun importBackup(uri: String): BackupImportSummary

    /** 読み込んだバックアップの設定を戻す。戻した数を返す(アプリのロック・オフラインモードなどは戻さない) */
    suspend fun restoreSettings(settings: BackupSettings): Int

    suspend fun storageUsage(): StorageUsage

    /**
     * ブックマーク・タグ・会話と検索用データ・添付ファイルをすべて削除する。
     * [resetSettings] が true なら設定と API キーも初期化する(端末内モデルは残す)。
     */
    suspend fun deleteAllData(resetSettings: Boolean)
}
