package com.unchunks.echomark.domain.provider

import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface LlmProvider {
    /**
     * 保存内容([input])から要約・タグ・カテゴリを1回の推論でまとめて生成する。
     * 中身の種類([AnalysisInput.kind])に合わせて、要約の主役(記事の要点・動画で話している内容・画像に写っているものなど)を変える。
     * @param existingTags 既存のタグ名(優先する順)。合うものがあれば新しく作らずに使わせる。
     *   プロンプトに入れる量は実装ごとの予算で切り詰める
     * 本文が上限より長ければ、部分ごとに要約してからまとめる。
     * @throws ModelNotAvailableException 利用に必要なモデルが未取得のとき
     * @throws NothingToAnalyzeException ファイルのブックマークで、本文が空でファイルも AI に渡せないとき
     * @throws LlmException クラウド API の呼び出しに失敗したとき(キー未設定・拒否など)
     */
    suspend fun analyze(input: AnalysisInput, existingTags: List<String>): BookmarkAnalysis

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

/**
 * ファイル(画像・音声など)のブックマークで、取り出したテキストが無く、ファイルそのものも AI に渡せない
 * (端末内 AI・ファイルの送信がオフ・提供元が受け付けない種類など)ため、要約の材料が無いことを表す。
 * ファイル名だけの要約を作らず、設定を変えた後の再処理を待つ。
 */
class NothingToAnalyzeException(message: String = "要約に使える中身がありません") : Exception(message)
