package com.unchunks.echomark.data.local

import android.content.Context
import com.google.mediapipe.framework.MediaPipeException
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextFormatContext
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingInputBuilder
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe の TextEmbedder による埋め込み。
 * ユーザーが取り込んだモデル([EmbeddingModelManager])があればそれを、無ければアセット同梱の EmbeddingGemma を使う。
 * 使うモデルが変わったら、次の埋め込みの前に TextEmbedder を閉じて作り直す。
 */
@Singleton
class OnDeviceEmbeddingProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: EmbeddingModelManager,
    private val dispatcherProvider: DispatcherProvider
) : EmbeddingProvider {

    override val profile: EmbeddingModelProfile get() = modelManager.activeProfile()

    private var textEmbedder: TextEmbedder? = null

    /** [textEmbedder] を作ったときのモデルの識別子([EmbeddingModelManager.sourceKey]) */
    private var loadedSourceKey: String? = null

    /** ファイルをメモリマップして読み込んだときの参照(TextEmbedder を閉じるまで保持する) */
    private var mappedModel: ByteBuffer? = null

    /** 埋め込みの実行と TextEmbedder の作成・破棄を直列にする(使用中に閉じてしまわないため)。 */
    private val embedMutex = Mutex()

    /** [prewarm] の読み込みを動かす(呼び出し元のワーカーが終わっても読み込みは続ける)。 */
    private val prewarmScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    private var prewarmJob: Job? = null

    /**
     * 現在のモデルに合った TextEmbedder を返す。モデルが切り替わっていたら閉じて作り直す。
     * [embedMutex] を取ってから呼ぶこと。
     * @throws EmbeddingUnavailableException モデル(assets・取り込んだファイル)が無いなどで初期化できないとき。
     *   呼び出し側は埋め込みをスキップし、検索はキーワードのみにフォールバックする
     */
    private fun ensureInitialized(): TextEmbedder {
        val sourceKey = modelManager.sourceKey()
        textEmbedder?.let { current ->
            if (loadedSourceKey == sourceKey) return current
            Timber.i("埋め込みモデルが切り替わったため読み込み直す: $loadedSourceKey -> $sourceKey")
            closeEmbedder()
        }
        val file = modelManager.modelFile()
        val loadStart = System.nanoTime()
        try {
            val embedder = if (file != null) createFromFile(file) else createFromAsset()
            textEmbedder = embedder
            loadedSourceKey = sourceKey
            AiPerfLog.record("embedding.load", (System.nanoTime() - loadStart) / 1_000_000L, sourceKey)
            return embedder
        } catch (e: Exception) {
            // assets 未同梱(gitignore 対象)の開発ビルドや、MediaPipe が読めない取り込みファイルで発生する
            Timber.w(e, "埋め込みモデルの初期化に失敗")
            throw EmbeddingUnavailableException(e)
        }
    }

    private fun closeEmbedder() {
        try {
            textEmbedder?.close()
        } catch (e: Exception) {
            Timber.w(e, "埋め込みモデルを閉じるのに失敗")
        }
        textEmbedder = null
        loadedSourceKey = null
        mappedModel = null
    }

    private fun createFromAsset(): TextEmbedder =
        create(BaseOptions.builder().setModelAssetPath(BUNDLED_ASSET_PATH).build())

    /**
     * 取り込んだファイルから作る。まず絶対パスを渡し(setModelAssetPath は assets 内の相対パスのほか
     * ファイルシステムのパスも受け付ける、というのが通常の使い方だが未確認)、
     * 失敗したらメモリマップしたバッファ(setModelAssetBuffer)で試す。
     * 取り込み済みのモデルが読めないときに同梱のモデルへ黙って切り替えると、版とベクトルが食い違うため、
     * どちらも失敗したら例外にする。
     */
    private fun createFromFile(file: File): TextEmbedder {
        try {
            return create(BaseOptions.builder().setModelAssetPath(file.absolutePath).build())
        } catch (e: Exception) {
            Timber.w(e, "パス指定で埋め込みモデルを読めなかったためバッファ指定で試す")
        }
        RandomAccessFile(file, "r").use { raf ->
            // 読み取り専用の direct バッファ。マップはチャネルを閉じても有効
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            val embedder = create(BaseOptions.builder().setModelAssetBuffer(buffer).build())
            mappedModel = buffer
            return embedder
        }
    }

    private fun create(baseOptions: BaseOptions): TextEmbedder {
        val options = TextEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .build()
        return TextEmbedder.createFromOptions(context, options)
    }

    /**
     * 埋め込みモデルの読み込みをバックグラウンドで始める。AI 処理で LLM が要約を作っている間に重ねて読み込み、
     * 要約ができて埋め込みに進むときには読み込み済みにする。読み込み中は [embedMutex] を持つため、
     * 先に埋め込みが呼ばれても、読み込みが終わるのを待つだけで二重には読み込まない。
     */
    override fun prewarm() {
        synchronized(this) {
            if (prewarmJob?.isActive == true) return
            prewarmJob = prewarmScope.launch {
                try {
                    embedMutex.withLock { ensureInitialized() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EmbeddingUnavailableException) {
                    // モデルが無い環境。本番の埋め込みで同じ扱いになる
                    Timber.d("埋め込みモデルを先読みできない: ${e.message}")
                } catch (e: Exception) {
                    Timber.w(e, "埋め込みモデルの先読みに失敗")
                }
            }
        }
    }

    override suspend fun embedDocument(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)

    override suspend fun embedQuery(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)

    /**
     * モデルが読めるトークン数には上限があり、超えると切り詰めずに失敗する(同梱の EmbeddingGemma は 512)。
     * 入力は [EmbeddingInputBuilder] で予算に収めているが、トークン数は数えられないため、
     * 超えたと言われたら、そのトークン数から縮める文字数を見積もってやり直す
     * (埋め込みのテキストは大事なものから並べているため、末尾を削る)。
     */
    private suspend fun embedInternal(
        text: String,
        taskType: TextEmbedder.EmbeddingType
    ): FloatArray = withContext(dispatcherProvider.io) {
        embedMutex.withLock {
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
     * [embedMutex] を取ってから呼ぶこと。MediaPipe のグラフは一度エラーになると、以後はどの入力でも同じエラーで失敗し続けるため、
     * 失敗したら閉じて次の呼び出しで作り直す。
     */
    private fun embedLocked(text: String, formatContext: TextFormatContext): FloatArray {
        val embedder = ensureInitialized()
        return try {
            embedder.embed(text, formatContext).embeddingResult().embeddings().first().floatEmbedding()
        } catch (e: MediaPipeException) {
            closeEmbedder()
            throw e
        }
    }

    private companion object {
        const val BUNDLED_ASSET_PATH = "embeddinggemma-300m/embedding_gemma.task"
    }
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
