package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment

/** 端末内のファイル(画像・PDF・音声・動画・テキスト)をアプリ内へ取り込む。 */
interface AttachmentRepository {
    /**
     * [uri](SAF・共有で得た content:// の URI 文字列)のファイルをアプリ内へコピーする。
     * 大きすぎる・空き容量が足りない・対応していない形式・読めないときは [AttachmentException]。
     * 呼び出し元のコルーチンを取り消すとコピーを止め、途中のファイルは消す。
     */
    suspend fun importFile(uri: String): StoredAttachment
}
