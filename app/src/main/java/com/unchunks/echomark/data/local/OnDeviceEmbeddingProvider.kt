package com.unchunks.echomark.data.local

import android.content.Context
import com.google.mediapipe.framework.MediaPipeException
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextFormatContext
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceEmbeddingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : EmbeddingProvider {

    override val dimensions = 768
    override val modelVersion = "embedding-gemma-300m-mediapipe-v1"

    private var textEmbedder: TextEmbedder? = null

    // 作成・使用・破棄を直列化する。エラーの後は作り直すため、別のコルーチンが使用中のものを閉じないようにする
    private val mutex = Mutex()

    // TODO: EmbeddingGemma を assets 同梱から ModelManager 管理(ダウンロード)へ移行する
    /**
     * 必ず [mutex] を持った状態で呼ぶ。
     * @throws EmbeddingUnavailableException モデル(assets)が無いなどで初期化できないとき。
     *   呼び出し側は埋め込みをスキップし、検索はキーワードのみにフォールバックする
     */
    private fun embedderLocked(): TextEmbedder =
        textEmbedder ?: run {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("embeddinggemma-300m/embedding_gemma.task")
                .build()
            val options = TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            try {
                TextEmbedder.createFromOptions(context, options).also { textEmbedder = it }
            } catch (e: Exception) {
                // assets 未同梱(gitignore 対象)の開発ビルドなどで発生する
                Timber.w(e, "埋め込みモデルの初期化に失敗")
                throw EmbeddingUnavailableException(e)
            }
        }

    override suspend fun embedDocument(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)

    override suspend fun embedQuery(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)

    /**
     * モデルが読めるトークン数には上限(512)があり、超えると切り詰めずに失敗する。
     * トークン数は数えられないため、超えたと言われたら、そのトークン数から縮める文字数を見積もってやり直す
     * (埋め込みのテキストは大事なものから並べているため、末尾を削る)。
     */
    private suspend fun embedInternal(
        text: String,
        taskType: TextEmbedder.EmbeddingType
    ): FloatArray = withContext(dispatcherProvider.io) {
        mutex.withLock {
            val formatContext = TextFormatContext.builder().setTaskType(taskType).build()
            var input = text
            repeat(MAX_SHORTEN_ATTEMPTS) {
                try {
                    return@withLock embedLocked(input, formatContext)
                } catch (e: MediaPipeException) {
                    val shorter = shortenedToFitTokenLimit(input, e.message) ?: throw e
                    Timber.d("埋め込みの入力が長すぎるため短くする: ${input.length} → ${shorter.length} 文字")
                    input = shorter
                }
            }
            embedLocked(input, formatContext)
        }
    }

    /**
     * 必ず [mutex] を持った状態で呼ぶ。MediaPipe のグラフは一度エラーになると、以後はどの入力でも同じエラーで失敗し続けるため、
     * 失敗したら破棄して次の呼び出しで作り直す。
     */
    private fun embedLocked(text: String, formatContext: TextFormatContext): FloatArray {
        val embedder = embedderLocked()
        return try {
            embedder.embedFloats(text, formatContext)
        } catch (e: MediaPipeException) {
            textEmbedder = null
            runCatching { embedder.close() }
            throw e
        }
    }

    private fun TextEmbedder.embedFloats(text: String, formatContext: TextFormatContext): FloatArray =
        embed(text, formatContext).embeddingResult().embeddings().first().floatEmbedding()
}

/** 入力が長すぎたときに縮め直す回数の上限(見積もりが外れても、数回で収まるようにする) */
private const val MAX_SHORTEN_ATTEMPTS = 3

/** 見積もりの誤差(文字によってトークン数が違う)を見込んで、上限より少し短くする割合 */
private const val TOKEN_LIMIT_MARGIN = 0.9

/** MediaPipe のエラー: 「token_ids.size() + 2 <= max_seq_len_ (689 vs. 512) Input text is too long: 687 tokens.」 */
private val TOO_LONG_TOKENS = Regex("""Input text is too long: (\d+) tokens""")
private val MAX_SEQ_LEN = Regex("""max_seq_len_ \(\d+ vs\. (\d+)\)""")

/** エラーにトークンの上限が書かれていないときの上限(EmbeddingGemma の MediaPipe 版) */
private const val DEFAULT_MAX_SEQ_LEN = 512

/** 先頭・末尾に付く特別なトークンの数 */
private const val SPECIAL_TOKENS = 2

/**
 * 埋め込みの入力が長すぎるというエラー([errorMessage])なら、上限に収まると見込める長さに末尾を削った [text] を返す。
 * 別のエラー・それ以上短くできないときは null。
 */
internal fun shortenedToFitTokenLimit(text: String, errorMessage: String?): String? {
    val message = errorMessage ?: return null
    val tokens = TOO_LONG_TOKENS.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val maxSeqLen = MAX_SEQ_LEN.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_MAX_SEQ_LEN
    val maxTokens = maxSeqLen - SPECIAL_TOKENS
    if (tokens <= 0 || maxTokens <= 0) return null
    val estimated = (text.length * maxTokens.toDouble() / tokens * TOKEN_LIMIT_MARGIN).toInt()
    // 見積もりが縮まないときも必ず短くする
    var length = minOf(estimated, text.length - 1)
    // サロゲートペアの途中で切らない
    if (length > 0 && text[length - 1].isHighSurrogate()) length--
    return text.take(length).trimEnd().takeIf { it.isNotEmpty() }
}
