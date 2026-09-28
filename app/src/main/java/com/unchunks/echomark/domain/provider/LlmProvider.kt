package com.unchunks.echomark.domain.provider

import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage

interface LlmProvider {
    /**
     * テキストから要約・タグ・カテゴリを1回の推論でまとめて生成する。
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     */
    suspend fun analyze(text: String): BookmarkAnalysis

    /**
     * 保存済みブックマークの内容([context])を根拠にユーザーの質問へ回答する(RAG)。
     * @param context "[n] タイトル: 要約" 形式に整形済みの文脈(番号で引用できる)
     * @param history 直近の会話履歴(古い順。今回の [userMessage] は含まない)
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     */
    suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String
}

/** LLM のモデルファイルが端末に存在せず、推論できないことを表す。 */
class ModelNotAvailableException(message: String = "LLM モデルが未ダウンロードです") : Exception(message)
