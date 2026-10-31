package com.unchunks.echomark.domain.importer

/** 取り込むファイルを読めなかった・対応していない形式だった。[message] は利用者にそのまま見せる */
class LinkImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 利用者が選んだ書き出しファイルを読んで、取り込むリンクにする(端末の中の処理だけで、通信やログインはしない) */
interface LinkImportSource {
    /**
     * [uri](ファイル選択で得た content:// の URI 文字列)のファイルを読む。
     * 読めない・大きすぎる([MAX_IMPORT_BYTES])・対応していない形式のときは [LinkImportException]。
     */
    suspend fun read(uri: String): ImportParseResult
}
