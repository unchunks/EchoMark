package com.unchunks.echomark.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.unchunks.components.embedding.EmbeddingInputBuilder
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.AiPrewarmer
import com.unchunks.echomark.data.ai.BookmarkAnalyzer
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.LimitedAccess
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.isProcessingCancelled
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.domain.bookmark.model.summaryProfile
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
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
 *
 * 端末内 AI・長い本文・ファイルの解析など、要約の作成が長くかかるときは、通知つきのフォアグラウンドで実行する
 * ([shouldRunInForeground])。WorkManager の実行時間の上限(約 10 分)やバックグラウンドでの停止を受けにくくし、実行の開始も遅らせない。
 * フォアグラウンドにできなくても(バックグラウンドからの開始の制限など)処理はそのまま続け、
 * 通知の権限が無いときは通知が表示されないだけで、フォアグラウンドでの実行は有効。
 */
@HiltWorker
class BookmarkAiProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BookmarkRepository,
    private val bookmarkAnalyzer: BookmarkAnalyzer,
    private val embeddingProvider: EmbeddingProvider,
    private val aiPrewarmer: AiPrewarmer,
    private val stats: ProcessingStatsRepository,
    private val concurrencyLimiter: ProcessingConcurrencyLimiter,
    private val apiRateLimiter: ApiRateLimiter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("doWork attempt=$runAttemptCount")
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        if (bookmarkId == -1L) return Result.failure()

        // 段ごとの所要時間を、このブックマークの計測として `AiPerf` のログに出す(調べ方は AiPerfLog)
        return withContext(AiPerfLog.trace(bookmarkId)) {
            try {
                // 同時に動かす数の上限に達していれば、空くまで処理待ちのまま待つ
                AiPerfLog.measure("total", "attempt=$runAttemptCount") {
                    concurrencyLimiter.withPermit { process(bookmarkId) }
                }
            } finally {
                AiPerfLog.logSummary(bookmarkId)
            }
        }
    }

    private suspend fun process(bookmarkId: Long): Result {
        // 削除済みなら何もしない(処理すべきものが無いだけなので成功で終える)
        val bookmark = repository.getBookmarkById(bookmarkId) ?: return Result.success()
        // ユーザーが処理をやめたもの(一括の列に残っているワーク)は何もしない
        if (bookmark.isProcessingCancelled) return Result.success()

        // システムによる中断が続くなど、どの失敗でも上限を超えたものは打ち切る
        if (runAttemptCount >= MAX_NETWORK_ATTEMPTS) {
            repository.finishProcessing(bookmarkId, AiStatus.FAILED, ProcessingFailureReason.TOO_MANY_ATTEMPTS)
            return Result.success()
        }

        // 今の段を始めた日時。中断されたときに、この段のままなら処理待ちに戻す
        var stageStartedAt: Long? = null
        var currentStage: ProcessingStage? = null
        // 今の段をやり遂げたら、所要時間を記録する(残り時間の目安に使う)
        suspend fun recordStageDuration() {
            val stage = currentStage ?: return
            val startedAt = stageStartedAt ?: return
            stats.recordStageDuration(StageDurationKey(stage, bookmark.contentKind()), System.currentTimeMillis() - startedAt)
        }
        suspend fun startStage(stage: ProcessingStage) {
            currentStage = stage
            stageStartedAt = repository.startProcessingStage(bookmarkId, stage)
        }

        return try {
            val scope = inputData.getString(KEY_SCOPE)?.let { name -> AnalysisScope.entries.firstOrNull { it.name == name } }
                ?: AnalysisScope.ALL
            // 全部の処理は要約がまだ無いときだけ解析する。要約だけ・タグだけの再処理は必ず解析する
            val needsAnalysis = scope != AnalysisScope.ALL || bookmark.summary.isNullOrBlank()
            // 要約が済んでいて埋め込みも最新なら、段を始めずに完了にする
            if (needsAnalysis) {
                // クラウド API の呼び出しの上限(1 分あたり)に達していれば、枠が空くまで処理待ちのまま待つ
                apiRateLimiter.acquireIfApi()
                startStage(ProcessingStage.ANALYZE)
            } else if (repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion) {
                startStage(ProcessingStage.EMBED)
            }
            prewarm(bookmarkId, needsAnalysis, scope)
            var analysisOutcome = AnalysisOutcome.DONE
            var failureReason: ProcessingFailureReason? = null
            // この実行で作った要約。埋め込みに含めるため、作ったら埋め込みも作り直す
            var newSummary: String? = null

            // 工程1: 要約・タグ・カテゴリ(要約が既にあればスキップ)
            if (needsAnalysis) {
                try {
                    // 既存のタグを伝え、似たタグを増やさず使い回させる
                    // 種類に合った要約にし、ファイルがあればクラウド API にそのまま渡せるようにする
                    // (渡すかどうかは設定と提供元の対応で LlmProvider が決める)
                    // タグ付けと要約に別の AI を選んでいれば、それぞれの AI で作る
                    val attachment = analysisAttachmentOf(bookmark, applicationContext.filesDir)
                    val input = AnalysisInput(
                        title = bookmark.title,
                        text = bookmark.content.orEmpty(),
                        kind = bookmark.contentKind(),
                        attachment = attachment,
                        profile = bookmark.summaryProfile()
                    )
                    promoteToForegroundIfLong(bookmark, input, scope)
                    val analysis = AiPerfLog.measure("analysis", "textChars=${input.text.length}") {
                        bookmarkAnalyzer.analyze(input, repository.getTagNamesForAi(), scope)
                    }
                    // タグだけの再処理では要約に触れない
                    if (scope != AnalysisScope.TAGS) {
                        // 有料・会員限定で一部しか取得できていないページは、全文の要約ではないと分かるようにする
                        val summary = LimitedAccess.annotateSummary(analysis.summary, input.text)
                        repository.saveGeneratedSummary(bookmarkId, summary, bookmarkAnalyzer.summaryOrigin())
                        newSummary = summary
                    }
                    // 所要時間の目安は全部を作ったときだけ記録する(部分の再処理は短く、目安を下げてしまう)
                    if (scope == AnalysisScope.ALL) recordStageDuration()
                    // 要約だけの再処理ではタグ・カテゴリに触れない。
                    // 前回 AI が付けたタグは置き換える(ユーザーが付けたタグはそのまま)
                    if (scope != AnalysisScope.SUMMARY) {
                        repository.saveAiTags(bookmarkId, analysis.tags)
                        repository.updateCategory(bookmarkId, analysis.category)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    analysisOutcome = classifyAnalysisError(e)
                    // 再試行で回復しうる失敗は外側の catch でリトライさせる
                    if (analysisOutcome == AnalysisOutcome.RETRY) throw e
                    failureReason = failureReasonOf(e)
                    // モデル未取得・キー未設定などでも埋め込みは実行する。設定後に再実行される
                    Timber.i("解析を保留/中止: id=$bookmarkId outcome=$analysisOutcome (${e.javaClass.simpleName})")
                } catch (e: LinkageError) {
                    // AI のライブラリのクラスの初期化・ネイティブライブラリの読み込みに失敗した(各 LlmProvider で
                    // 例外に変えているが、変えていない経路の備え)。再試行しても同じ結果になるため諦め、埋め込みは続ける
                    Timber.e(e, "解析のライブラリを初期化できない: id=$bookmarkId")
                    analysisOutcome = AnalysisOutcome.GIVE_UP
                    failureReason = failureReasonOf(e)
                }
            }

            // 工程2: 埋め込み(現行モデルバージョンで保存済みならスキップ)
            // 埋め込みモデルが無い環境ではスキップし、検索はキーワードのみで動かす
            try {
                if (newSummary != null ||
                    repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion
                ) {
                    if (needsAnalysis) startStage(ProcessingStage.EMBED)
                    val text = EmbeddingInputBuilder.build(
                        bookmark.title, newSummary ?: bookmark.summary, bookmark.content, embeddingProvider.profile
                    )
                    val vector = AiPerfLog.measure("embedding", "chars=${text.length}") {
                        embeddingProvider.embedDocument(text)
                    }
                    repository.saveEmbedding(bookmarkId, vector, embeddingProvider.modelVersion)
                    recordStageDuration()
                }
            } catch (e: EmbeddingUnavailableException) {
                Timber.i("埋め込みモデルが無いため埋め込みをスキップ: id=$bookmarkId")
            }

            repository.finishProcessing(
                bookmarkId,
                when (analysisOutcome) {
                    AnalysisOutcome.DONE -> AiStatus.DONE
                    AnalysisOutcome.WAITING_SETUP -> AiStatus.WAITING_MODEL
                    // 拒否など再試行しても結果が変わらないもの。設定画面から手動で再処理できる
                    AnalysisOutcome.GIVE_UP, AnalysisOutcome.RETRY -> AiStatus.FAILED
                },
                failureReason
            )
            Result.success()
        } catch (e: CancellationException) {
            // 中断(実行時間の上限・制約の不成立・取り消しなど)で「処理中」のまま残さない。
            // 再実行されればそこで処理中に戻り、取り消されたまま再実行されなければ起動時に積み直す
            withContext(NonCancellable) { stageStartedAt?.let { repository.endProcessingStage(bookmarkId, it) } }
            throw e
        } catch (e: LinkageError) {
            // ライブラリの初期化の失敗(埋め込みなど)。Error のまま投げるとワークは失敗し、表示が「処理中」のまま残る。
            // 同じプロセスでは再試行しても同じ結果になるため、失敗として終える(設定画面から再処理できる)
            Timber.e(e, "AI 処理のライブラリを初期化できない: id=$bookmarkId")
            repository.finishProcessing(bookmarkId, AiStatus.FAILED, failureReasonOf(e))
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AI 処理に失敗: id=$bookmarkId attempt=$runAttemptCount")
            // 再試行の間隔は WorkManager の指数バックオフ(30 秒から倍々、最大 5 時間)に任せる
            if (runAttemptCount + 1 >= maxAttemptsFor(e)) {
                repository.finishProcessing(bookmarkId, AiStatus.FAILED, failureReasonOf(e))
                Result.success()
            } else {
                // 再試行を待つ間は処理待ちにする(実行しているワークは無い)
                repository.markProcessingRetrying(bookmarkId, failureReasonOf(e))
                Result.retry()
            }
        }
    }

    /**
     * 要約の作成が長くかかりそうなら、通知つきのフォアグラウンドにする。
     * 始められなくても(Android 12 以降のバックグラウンドからの開始の制限など)処理は続ける。
     */
    private suspend fun promoteToForegroundIfLong(bookmark: Bookmark, input: AnalysisInput, scope: AnalysisScope) {
        if (!shouldRunInForeground(aiPrewarmer.usesLocalLlm(scope), input.text.length, input.attachment != null)) return
        try {
            setForeground(AiProcessingNotification.foregroundInfo(applicationContext, bookmark.title))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AI 処理をフォアグラウンドにできません。このまま続けます: id=${bookmark.id}")
        }
    }

    /**
     * WorkManager が通知を求めたときの内容(Android 11 以前のエクスペディットなど)。最小が Android 12 のこのアプリでは
     * 通常は呼ばれないが、CoroutineWorker の既定の実装は例外を投げるため用意する。ブックマークのタイトルが分かれば出す。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val bookmarkId = inputData.getLong(KEY_BOOKMARK_ID, -1L)
        val title = if (bookmarkId == -1L) null else repository.getBookmarkById(bookmarkId)?.title
        return AiProcessingNotification.foregroundInfo(applicationContext, title)
    }

    /**
     * 使うモデルの読み込みを先に始める(完了は待たない)。要約を作る間に、端末内 LLM の読み込みが済んでいなければ重ねて進める。
     * 前段の本文取得・取り出しのワーカーが先に始めていれば、ここでは何も起きない。
     *
     * 埋め込みモデルの先読みは、端末内 LLM を使わないとき(クラウド API で要約するとき)だけ要約と重ねる。
     * 端末内 LLM(数百MB〜数GB)と同時に読み込むとメモリの使用量が一気に増え、端末によってはアプリが強制終了されるため、
     * そのときは要約の後に埋め込みの中で読み込む。
     */
    private suspend fun prewarm(bookmarkId: Long, needsSummary: Boolean, scope: AnalysisScope) {
        val localLlmRuns = needsSummary && aiPrewarmer.prewarmLocalLlmIfUsed(scope)
        if (!localLlmRuns &&
            (needsSummary || repository.getEmbeddingModelVersion(bookmarkId) != embeddingProvider.modelVersion)
        ) {
            embeddingProvider.prewarm()
        }
    }

    companion object {
        const val KEY_BOOKMARK_ID = "bookmark_id"

        /** 作り直すもの(AnalysisScope の名前)。無ければ全部 */
        const val KEY_SCOPE = "analysis_scope"
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
    is LlmException.Timeout,
    // 端末内 AI のライブラリを初期化できない。同じプロセスでは何度試しても同じ失敗になる
    is LlmException.EngineUnavailable -> AnalysisOutcome.GIVE_UP
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
    val file = attachmentFileOf(filesDir, relativePath) ?: return null
    return AnalysisAttachment(path = file.absolutePath, mimeType = mimeType, sizeBytes = file.length())
}

/**
 * ブックマークの [relativePath](filesDir からの相対パス)が指すファイル。アプリの領域の外を指す・ファイルが無いときは null
 * (バックアップの復元などで不正な値が入っても、領域の外のファイルは読まない)。
 */
internal fun attachmentFileOf(filesDir: File, relativePath: String): File? {
    val root = filesDir.canonicalFile
    val file = File(root, relativePath).canonicalFile
    return file.takeIf { it.startsWith(root) && it.isFile }
}
