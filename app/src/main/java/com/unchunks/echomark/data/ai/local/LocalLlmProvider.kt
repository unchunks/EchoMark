package com.unchunks.echomark.data.ai.local

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.data.ai.model.ModelSpecs
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe LLM Inference (Gemma) によるローカル LLM。
 * モデルは [ModelManager] が管理するファイルを使い、初回利用時に遅延初期化する。
 */
@Singleton
class LocalLlmProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val dispatcherProvider: DispatcherProvider
) : LlmProvider {

    private val spec = ModelSpecs.GEMMA_LLM

    private var llmInference: LlmInference? = null
    private val initMutex = Mutex()

    // LlmInference は同時に複数の生成を実行できないため直列化する
    private val inferenceMutex = Mutex()

    private suspend fun ensureInitialized(): LlmInference {
        llmInference?.let { if (modelManager.isAvailable(spec)) return it }
        return initMutex.withLock {
            if (!modelManager.isAvailable(spec)) {
                // モデルが削除された場合は保持しているエンジンも破棄する
                llmInference?.close()
                llmInference = null
                throw ModelNotAvailableException()
            }
            llmInference ?: run {
                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelManager.file(spec).absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                LlmInference.createFromOptions(context, options).also { llmInference = it }
            }
        }
    }

    private suspend fun generate(prompt: String): String =
        withContext(dispatcherProvider.default) {
            val engine = ensureInitialized()
            inferenceMutex.withLock { engine.generateResponse(prompt) }
        }

    override suspend fun analyze(text: String): BookmarkAnalysis {
        val response = generate(buildAnalyzePrompt(text))
        return parseAnalysis(response, text)
    }

    override suspend fun chat(userMessage: String, context: List<String>): String {
        return generate(buildChatPrompt(userMessage, context)).trim()
    }

    // 注意: 本文を埋め込むため trimIndent は使わず、行の連結で組み立てる
    private fun buildAnalyzePrompt(text: String): String = listOf(
        "あなたはブックマーク整理アシスタントです。次の保存内容を分析し、JSONのみを出力してください。",
        "説明文やコードブロックは出力しないでください。",
        "",
        "出力形式:",
        """{"summary": "100文字以内の日本語の要約", "tags": ["タグ1", "タグ2", "タグ3"], "category": "カテゴリ名1つ"}""",
        "",
        "ルール:",
        "- summary は日本語で簡潔に。",
        "- tags は内容を表す短い単語を最大5個。",
        "- category は「技術」「ニュース」「レシピ」「学習」「仕事」「趣味」「その他」のように短い1語。",
        "",
        "保存内容:",
        text.take(MAX_INPUT_CHARS)
    ).joinToString("\n")

    private fun buildChatPrompt(userMessage: String, context: List<String>): String {
        val snippets = context.take(MAX_CONTEXT_ITEMS)
            .mapIndexed { i, s -> "[${i + 1}] ${s.take(MAX_SNIPPET_CHARS)}" }
        val lines = if (snippets.isEmpty()) {
            listOf(
                "あなたはユーザーの保存したブックマークに答えるアシスタントです。",
                "関連する保存内容は見つかりませんでした。その旨を伝えたうえで、分かる範囲で簡潔に日本語で答えてください。",
                "",
                "質問: $userMessage"
            )
        } else {
            listOf(
                "あなたはユーザーの保存したブックマークに答えるアシスタントです。",
                "以下の保存内容だけを根拠に、質問へ日本語で簡潔に答えてください。",
                "根拠がない場合は、分からないと答えてください。参照した番号を [1] のように示してください。",
                "",
                "保存内容:"
            ) + snippets.flatMap { listOf(it, "") } + listOf("質問: $userMessage")
        }
        return lines.joinToString("\n")
    }

    /** LLM の出力から JSON を取り出して解析する。失敗時は入力テキストからフォールバックを作る。 */
    private fun parseAnalysis(response: String, source: String): BookmarkAnalysis {
        return try {
            val start = response.indexOf('{')
            val end = response.lastIndexOf('}')
            require(start in 0 until end) { "JSON が見つからない" }
            val json = JSONObject(response.substring(start, end + 1))

            val summary = json.optString("summary").trim()
            val tagsArray = json.optJSONArray("tags")
            val tags = buildList {
                if (tagsArray != null) {
                    for (i in 0 until tagsArray.length()) {
                        val tag = tagsArray.optString(i).trim().removePrefix("#")
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
            }.distinct().take(MAX_TAGS)
            val category = json.optString("category").trim().ifEmpty { DEFAULT_CATEGORY }

            BookmarkAnalysis(
                summary = summary.ifEmpty { fallbackSummary(source) },
                tags = tags,
                category = category
            )
        } catch (e: Exception) {
            Timber.w(e, "LLM 出力の JSON 解析に失敗。フォールバックを使用")
            BookmarkAnalysis(
                summary = fallbackSummary(source),
                tags = emptyList(),
                category = DEFAULT_CATEGORY
            )
        }
    }

    private fun fallbackSummary(source: String): String = source.trim().take(FALLBACK_SUMMARY_CHARS)

    companion object {
        private const val MAX_TOKENS = 2048
        private const val MAX_INPUT_CHARS = 2000
        private const val MAX_CONTEXT_ITEMS = 5
        private const val MAX_SNIPPET_CHARS = 600
        private const val MAX_TAGS = 5
        private const val FALLBACK_SUMMARY_CHARS = 100
        private const val DEFAULT_CATEGORY = "未分類"
    }
}
