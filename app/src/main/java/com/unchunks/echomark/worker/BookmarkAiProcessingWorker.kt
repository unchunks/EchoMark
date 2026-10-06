package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unchunks.echomark.data.ai.BookmarkAnalyzer
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 保存されたブックマークの AI 処理(要約・タグ・カテゴリ・埋め込み)を行う。
 * 各工程は冪等: 済みの工程はスキップするため、リトライしても全体をやり直さない。
 *
 * 結果はブックマークの [AiStatus] で表す。諦めた(FAILED)ときもワークとしては成功で終え、
 * 一括の再処理の列([BookmarkWorkScheduler.enqueueSequential])の後続を止めない。
 */
@HiltWorker
class BookmarkAiProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val bookmarkAnalyzer: BookmarkAnalyzer,
    private val embeddingProvider: EmbeddingProvider
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("doWork attempt=$runAttemptCount")
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.failure()

        // 削除済みなら何もしない(処理すべきものが無いだけなので成功で終える)
        val bookmark = repository.getBookmarkById(bookmarkId) ?: return Result.success()

        // システムによる中断が続くなど、どの失敗でも上限を超えたものは打ち切る
        if (runAttemptCount >= MAX_NETWORK_ATTEMPTS) {
            repository.updateAiStatus(bookmarkId, AiStatus.FAILED)
            return Result.success()
        }

        return try {
            repository.updateAiStatus(bookmarkId, AiStatus.PROCESSING)
            var analysisOutcome = AnalysisOutcome.DONE
            // この実行で作った要約。埋め込みに含めるため、作ったら埋め込みも作り直す
            var newSummary: String? = null

            // 工程1: 要約・タグ・カテゴリ(要約が既にあればスキップ)
            if (bookmark.summary.isNullOrBlank()) {
                try {
                    // 既存のタグを伝え、似たタグを増やさず使い回させる
                    // 種類に合った要約にし、ファイルがあればクラウド API にそのまま渡せるようにする
                    // (渡すかどうかは設定と提供元の対応で LlmProvider が決める)
                    // タグ付けと要約に別の AI を選んでいれば、それぞれの AI で作る
                    val input = AnalysisInput(
                        title = bookmark.title,
                        text = bookmark.content.orEmpty(),
                        kind = bookmark.contentKind(),
                        attachment = analysisAttachmentOf(bookmark, applicationContext.filesDir)
                    )
                    val analysis = bookmarkAnalyzer.analyze(input, repository.getTagNamesForAi())
                    repository.updateSummary(bookmarkId, analysis.summary)
                    newSummary = analysis.summary
                    // 前回 AI が付けたタグは置き換える(ユーザーが付けたタグはそのまま)
                    repository.saveAiTags(bookmarkId, analysis.tags)
                    repository.updateCategory(bookmarkId, analysis.category)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    analysisOutcome = classifyAnalysisError(e)
                    // 再試行で回復しうる失敗は外側の catch でリトライさせる
                    if (analysisOutcome == AnalysisOutcome.RETRY) throw e
                    // モデル未取得・キー未設定などでも埋め込みは実行する。設定後に再実行される
                    Timber.i("解析を保留/中止: id=$bookmarkId outcome=$analysisOutcome (${e.javaClass.simpleName})")
                }
            }

            // 工程2: 埋め込み(現行モデルバージョンで保存済みならスキップ)
            // 埋め込みモデルが無い環境ではスキップし、検索はキーワードのみで動かす
            try {
                if (newSummary != null ||
                    repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion
                ) {
                    val text = embeddingTextOf(bookmark.title, newSummary ?: bookmark.summary, bookmark.content)
                    val vector = embeddingProvider.embedDocument(text)
                    repository.saveEmbedding(bookmarkId, vector, embeddingProvider.modelVersion)
                }
            } catch (e: EmbeddingUnavailableException) {
                Timber.i("埋め込みモデルが無いため埋め込みをスキップ: id=$bookmarkId")
            }

            repository.updateAiStatus(
                bookmarkId,
                when (analysisOutcome) {
                    AnalysisOutcome.DONE -> AiStatus.DONE
                    AnalysisOutcome.WAITING_SETUP -> AiStatus.WAITING_MODEL
                    // 拒否など再試行しても結果が変わらないもの。設定画面から手動で再処理できる
                    AnalysisOutcome.GIVE_UP, AnalysisOutcome.RETRY -> AiStatus.FAILED
                }
            )
            Result.success()
        } catch (e: CancellationException) {
            // 中断(実行時間の上限・制約の不成立・取り消しなど)で「処理中」のまま残さない。
            // 再実行されればそこで処理中に戻り、取り消されたまま再実行されなければ起動時に積み直す
            withContext(NonCancellable) { repository.markProcessingInterrupted(bookmarkId) }
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AI 処理に失敗: id=$bookmarkId attempt=$runAttemptCount")
            // 再試行の間隔は WorkManager の指数バックオフ(30 秒から倍々、最大 5 時間)に任せる
            if (runAttemptCount + 1 >= maxAttemptsFor(e)) {
                repository.updateAiStatus(bookmarkId, AiStatus.FAILED)
                Result.success()
            } else {
                repository.updateAiStatus(bookmarkId, AiStatus.PENDING)
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"
    }
}

/** サーバー障害など、再試行で回復しうる失敗の試行回数の上限。 */
private const val MAX_ATTEMPTS = 5

/** レート制限の試行回数の上限。待てば回復するため多めにする。 */
private const val MAX_RATE_LIMITED_ATTEMPTS = 10

/**
 * 通信エラーの試行回数の上限。クラウド API の処理はネットワーク接続を条件にしている(オフラインの間は試行しない)ため、
 * 接続中なのに通信できない状態(キャプティブポータルなど)が続く場合だけ数える。バックオフの上限(5 時間)と合わせて数日待つ。
 */
private const val MAX_NETWORK_ATTEMPTS = 20

/**
 * 再試行で回復しうる失敗([AnalysisOutcome.RETRY] など)を、何回目の試行まで続けるか。
 * Result.retry() には待ち時間を指定できないため、Retry-After はワーカーでは使わず指数バックオフ(30 秒から倍々)に任せる
 * (Claude は SDK の自動再試行が Retry-After を考慮する)。
 */
internal fun maxAttemptsFor(e: Exception): Int = when (e) {
    is LlmException.Network -> MAX_NETWORK_ATTEMPTS
    is LlmException.RateLimited -> MAX_RATE_LIMITED_ATTEMPTS
    else -> MAX_ATTEMPTS
}

/** 解析(要約・タグ)工程の結果。 */
internal enum class AnalysisOutcome {
    DONE,
    /** モデル未取り込み・API キー未設定/無効・モデル ID 不正など、設定の変更を待つ */
    WAITING_SETUP,
    /** 拒否・安全フィルタ・生成の時間切れ(端末内 AI の繰り返し)など、再試行しても結果が変わらない */
    GIVE_UP,
    /** レート制限・通信断・サーバー障害など、時間をおけば回復しうる */
    RETRY
}

/** 解析工程の例外を、ワーカーの振る舞いに対応づける。 */
internal fun classifyAnalysisError(e: Exception): AnalysisOutcome = when (e) {
    // 中身を読み取れないファイル。クラウド API への切り替えやファイルの送信をオンにした後に、再処理で要約できる
    is NothingToAnalyzeException,
    is ModelNotAvailableException,
    is LlmException.ApiKeyMissing,
    is LlmException.InvalidApiKey,
    is LlmException.BadRequest -> AnalysisOutcome.WAITING_SETUP
    is LlmException.Refused,
    is LlmException.Timeout -> AnalysisOutcome.GIVE_UP
    else -> AnalysisOutcome.RETRY
}

/**
 * AI に渡す元のファイル。ファイルでない・MIME タイプが不明・ファイルが無い(削除された)ときは null。
 * [Bookmark.filePath] は filesDir からの相対パス。バックアップの復元などで不正な値が入っても、
 * アプリの領域の外のファイルは読まない。
 */
internal fun analysisAttachmentOf(bookmark: Bookmark, filesDir: File): AnalysisAttachment? {
    val relativePath = bookmark.filePath?.takeIf { it.isNotBlank() } ?: return null
    val mimeType = bookmark.mimeType?.takeIf { it.isNotBlank() } ?: return null
    val root = filesDir.canonicalFile
    val file = File(root, relativePath).canonicalFile
    if (!file.startsWith(root) || !file.isFile) return null
    return AnalysisAttachment(path = file.absolutePath, mimeType = mimeType, sizeBytes = file.length())
}

/**
 * 埋め込み(ベクトル検索)に使うテキスト。タイトル・要約・本文の先頭の順に並べる。
 * 埋め込みモデルが読める長さには上限があり、長い本文は先頭しか反映されないため、
 * 中身を短くまとめた要約を先に置く(画像・音声など本文の無いものも、要約で検索できるようにする)。
 */
internal fun embeddingTextOf(title: String, summary: String?, content: String?): String =
    listOfNotNull(
        title.takeIf { it.isNotBlank() },
        summary?.takeIf { it.isNotBlank() },
        content?.take(EMBEDDING_CONTENT_CHARS)?.takeIf { it.isNotBlank() }
    ).joinToString("\n")

/** 埋め込みに含める本文の先頭の文字数 */
private const val EMBEDDING_CONTENT_CHARS = 1_000
