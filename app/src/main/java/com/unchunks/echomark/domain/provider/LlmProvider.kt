package com.unchunks.echomark.domain.provider

import com.unchunks.echomark.domain.model.BookmarkAnalysis

interface LlmProvider {
    /**
     * テキストから要約・タグ・カテゴリを1回の推論でまとめて生成する。
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     */
    suspend fun analyze(text: String): BookmarkAnalysis

    /**
     * 保存済みブックマークの内容([context])を根拠にユーザーの質問へ回答する(RAG)。
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     */
    suspend fun chat(userMessage: String, context: List<String>): String
}

/** LLM のモデルファイルが端末に存在せず、推論できないことを表す。 */
class ModelNotAvailableException(message: String = "LLM モデルが未ダウンロードです") : Exception(message)
