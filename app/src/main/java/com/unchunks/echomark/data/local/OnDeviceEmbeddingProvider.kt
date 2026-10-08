package com.unchunks.echomark.data.local

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextFormatContext
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingModelProfile
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
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
        try {
            val embedder = if (file != null) createFromFile(file) else createFromAsset()
            textEmbedder = embedder
            loadedSourceKey = sourceKey
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

    override suspend fun embedDocument(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_DOCUMENT)

    override suspend fun embedQuery(text: String): FloatArray =
        embedInternal(text, TextEmbedder.EmbeddingType.RETRIEVAL_QUERY)

    private suspend fun embedInternal(
        text: String,
        taskType: TextEmbedder.EmbeddingType
    ): FloatArray = withContext(dispatcherProvider.io) {
        embedMutex.withLock {
            val embedder = ensureInitialized()
            val formatContext = TextFormatContext.builder().setTaskType(taskType).build()
            val result = embedder.embed(text, formatContext)
            result.embeddingResult().embeddings().first().floatEmbedding()
        }
    }

    private companion object {
        const val BUNDLED_ASSET_PATH = "embeddinggemma-300m/embedding_gemma.task"
    }
}
