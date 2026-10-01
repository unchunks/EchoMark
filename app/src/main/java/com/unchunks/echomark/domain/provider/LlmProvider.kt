package com.unchunks.echomark.domain.provider

import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface LlmProvider {
    /**
     * テキストから要約・タグ・カテゴリを1回の推論でまとめて生成する。
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     * @throws LlmException クラウド API の呼び出しに失敗したとき(キー未設定・拒否など)
     */
    suspend fun analyze(text: String): BookmarkAnalysis

    /**
     * 保存済みブックマークの内容([context])を根拠にユーザーの質問へ回答する(RAG)。
     * @param context "[n] タイトル: 要約" 形式に整形済みの文脈(番号で引用できる)
     * @param history 直近の会話履歴(古い順。今回の [userMessage] は含まない)
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     * @throws LlmException クラウド API の呼び出しに失敗したとき
     */
    suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String

    /**
     * [chat] のストリーミング版。生成されたテキストを増分(前回 emit からの差分)で流す。
     * 収集側のキャンセルで生成も止まる。失敗は [chat] と同じ例外で Flow が終了する。
     * 既定実装は [chat] の結果を1回だけ emit する。
     */
    fun chatStream(userMessage: String, context: List<String>, history: List<ChatMessage>): Flow<String> =
        flow { emit(chat(userMessage, context, history)) }
}

/** LLM のモデルファイルが端末に存在せず、推論できないことを表す。 */
class ModelNotAvailableException(message: String = "LLM モデルが未取り込みです") : Exception(message)
