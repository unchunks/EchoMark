package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.ChatAttachmentContext

/** チャットに添付されたファイルを、質問の根拠にする本文にする。 */
interface ChatAttachmentSource {
    /**
     * [uri] のファイルを取り込み、本文を取り出す。
     * @throws com.unchunks.echomark.domain.model.ChatAttachmentException 使えないとき(画面にそのまま出せる文言)
     */
    suspend fun load(uri: String): ChatAttachmentContext
}
