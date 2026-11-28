package com.unchunks.echomark.domain.importer

/*
 * 他のサービスの書き出しファイルからの取り込み。ファイルの読み取り(ImportedLink・ImportParseResult・LinkImportParser)は
 * 部品の bookmark-file。ここには、取り込んだあとの保存の結果と、EchoMark が決める上限だけを置く。
 */

/** 取り込みの結果 */
data class ImportResult(
    /** 新しく保存した件数 */
    val added: Int,
    /** 同じ URL が保存済みで、読み飛ばした件数 */
    val duplicates: Int,
    /** 保存に失敗した件数 */
    val failed: Int = 0,
    /** ページの取得・要約を順番に始めた(夜間にまとめて処理する設定などで始めなかったときは false) */
    val processingStarted: Boolean = false
)

/** 読み込むファイルの大きさの上限(バイト) */
const val MAX_IMPORT_BYTES: Long = 30L * 1024 * 1024
