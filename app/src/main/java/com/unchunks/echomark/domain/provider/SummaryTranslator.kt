package com.unchunks.echomark.domain.provider

import com.unchunks.echomark.domain.model.SummaryLanguage

/** 要約を別の言語に翻訳する(#24)。翻訳は保存せず、画面に出すだけ。 */
interface SummaryTranslator {
    /**
     * [text] を [target] の言語に翻訳する。「要約」の設定の AI(端末内・クラウド API)で行う。
     * @throws ModelNotAvailableException 端末内のモデルが未取り込みのとき
     * @throws com.unchunks.components.llm.LlmException クラウド API の呼び出しに失敗したとき
     * @throws IllegalStateException 応答から翻訳を取り出せなかったとき
     */
    suspend fun translate(text: String, target: SummaryLanguage): String
}
