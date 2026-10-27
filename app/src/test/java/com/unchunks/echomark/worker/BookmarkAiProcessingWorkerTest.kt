package com.unchunks.echomark.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.R
import com.unchunks.echomark.data.ai.AiPerfLog
import com.unchunks.echomark.data.ai.AiPrewarmer
import com.unchunks.echomark.data.ai.AiPrompts
import com.unchunks.echomark.data.ai.BookmarkAnalyzer
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.data.repository.BookmarkRepositoryImpl
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.model.AnalysisAttachment
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.provider.NothingToAnalyzeException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeEmbeddingProvider
import com.unchunks.echomark.testing.FakeLlmProvider
import com.unchunks.echomark.testing.FakeProcessingStatsRepository
import com.unchunks.echomark.testing.embeddingBox
import com.unchunks.echomark.testing.inMemoryBoxStore
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import io.objectbox.BoxStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AI 処理ワーカーの再試行・失敗の判断と、削除済みのブックマークの扱い。 */
@RunWith(AndroidJUnit4::class)
class BookmarkAiProcessingWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var boxStore: BoxStore
    private lateinit var workManager: WorkManager
    private lateinit var repository: BookmarkRepositoryImpl
    private val llm = ScriptedLlmProvider()
    private val embedding = RecordingEmbeddingProvider()
    /** 端末内モデルの先読みの呼び出しを数える(AiPrewarmer の先)。 */
    private val localLlmForPrewarm = FakeLlmProvider()
    private val settings = FakeAppSettingsRepository(backend = LlmBackend.LOCAL)
    private val stats = FakeProcessingStatsRepository()

    @Before
    fun setUp() {
        AiPerfLog.clear()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        boxStore = inMemoryBoxStore()
        workManager = initTestWorkManager(context)
        repository = BookmarkRepositoryImpl(
            bookmarkDao = db.bookmarkDao(),
            tagDao = db.tagDao(),
            vectorSearch = VectorSearchDataSource(boxStore.embeddingBox()),
            dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined),
            workScheduler = BookmarkWorkScheduler(workManager, settings),
            embeddingProvider = FakeEmbeddingProvider(),
            attachmentStore = AttachmentStore(context, TestDispatcherProvider(Dispatchers.Unconfined))
        )
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
        db.close()
        boxStore.close()
    }

    private suspend fun insertBookmark(): Long = db.bookmarkDao().insert(
        Bookmark(type = BookmarkType.TEXT, content = "本文", title = "メモ", createdAt = 1L, lastAccessedAt = 1L)
            .toEntity()
    )

    private suspend fun runWorker(bookmarkId: Long, runAttemptCount: Int): ListenableWorker.Result =
        buildWorker(bookmarkId, runAttemptCount).doWork()

    private fun buildWorker(bookmarkId: Long, runAttemptCount: Int = 0): BookmarkAiProcessingWorker {
        val analyzer = BookmarkAnalyzer(LlmProviderResolver(llm, { llm }, settings, FakeApiKeyRepository()), settings)
        val worker = TestListenableWorkerBuilder<BookmarkAiProcessingWorker>(context)
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = BookmarkAiProcessingWorker(
                    appContext, workerParameters, repository, analyzer, embedding,
                    AiPrewarmer(localLlmForPrewarm, settings), stats
                )
            })
            .build()
        return worker
    }

    private suspend fun statusOf(id: Long) = repository.getBookmarkById(id)!!.aiStatus

    @Test
    fun 成功すると要約とタグを保存して完了にする() = runBlocking {
        val id = insertBookmark()

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))

        assertEquals(AiStatus.DONE, statusOf(id))
        assertEquals("要約", repository.getBookmarkById(id)!!.summary)
    }

    @Test
    fun 既存のタグをAIに渡し_再処理ではAIのタグだけを付け直す() = runBlocking {
        val id = insertBookmark()
        repository.addTag(id, "自分のタグ")
        db.tagDao().addTagsToBookmark(insertBookmark2(), listOf("Android"), TagSource.USER)
        llm.tags = listOf("android", "古いAIタグ")

        runWorker(id, runAttemptCount = 0)

        assertEquals(listOf("Android", "自分のタグ"), llm.lastExistingTags)
        val first = repository.observeBookmark(id).first()!!
        // 表記ゆれは既存のタグにそろえる
        assertEquals(setOf("自分のタグ", "Android", "古いAIタグ"), first.tags.toSet())
        assertEquals(setOf("Android", "古いAIタグ"), first.aiTags)

        repository.reprocess(id)
        llm.tags = listOf("新しいAIタグ")
        runWorker(id, runAttemptCount = 0)

        val second = repository.observeBookmark(id).first()!!
        assertEquals(setOf("自分のタグ", "新しいAIタグ"), second.tags.toSet())
        assertEquals(setOf("新しいAIタグ"), second.aiTags)
        // どこにも付かなくなった AI のタグは消え、ユーザーのタグ(別のブックマークに付いている Android)は残る
        val allTags = db.tagDao().getAllTags().first().map { it.name }.toSet()
        assertEquals(setOf("自分のタグ", "新しいAIタグ", "Android"), allTags)
    }

    private suspend fun insertBookmark2(): Long = db.bookmarkDao().insert(
        Bookmark(type = BookmarkType.TEXT, content = "別の本文", title = "別のメモ", createdAt = 2L, lastAccessedAt = 2L)
            .toEntity()
    )

    @Test
    fun ファイルのブックマークは種類と元のファイルをAIに渡す() = runBlocking {
        val file = File(context.filesDir, "attachments/receipt.png").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1234))
        }
        val id = db.bookmarkDao().insert(
            Bookmark(
                type = BookmarkType.IMAGE, content = "合計 1,200円", title = "receipt.png", createdAt = 1L,
                lastAccessedAt = 1L, filePath = "attachments/receipt.png", mimeType = "image/png", fileSize = 1234L
            ).toEntity()
        )

        runWorker(id, runAttemptCount = 0)

        val input = llm.lastInput!!
        assertEquals(ContentKind.IMAGE, input.kind)
        assertEquals("合計 1,200円", input.text)
        assertEquals(AnalysisAttachment(file.canonicalPath, "image/png", 1234L), input.attachment)
    }

    @Test
    fun 中身を読み取れないファイルは準備待ちにする() = runBlocking {
        val id = insertBookmark()
        llm.failure = NothingToAnalyzeException()

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))

        // 端末内 AI では読めない写真など。クラウド API に切り替えた後などに再処理で要約できる
        assertEquals(AiStatus.WAITING_MODEL, statusOf(id))
        assertEquals(null, repository.getBookmarkById(id)!!.summary)
    }

    @Test
    fun 埋め込みには作った要約を含め_要約を作り直したら埋め込みも作り直す() = runBlocking {
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 0)
        assertEquals(listOf("メモ\n要約\n本文"), embedding.documents)

        repository.reprocess(id)
        runWorker(id, runAttemptCount = 0)
        // 埋め込みモデルは同じでも、要約が変わったため作り直す
        assertEquals(2, embedding.documents.size)
    }

    @Test
    fun 端末内モデルで要約するときは端末内モデルだけ先読みし_埋め込みモデルは同時に読み込まない() = runBlocking {
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 0)

        assertEquals(1, localLlmForPrewarm.prewarmCalls)
        // 端末内 LLM と同時に読み込むとメモリが一気に増えて強制終了されうるため、要約の後に読み込む
        assertEquals(0, embedding.prewarmCalls)
    }

    @Test
    fun クラウドAPIの設定では端末内モデルを先読みしないが埋め込みモデルは先読みする() = runBlocking {
        settings.setBackendForAll(LlmBackend.API)
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 0)

        assertEquals(0, localLlmForPrewarm.prewarmCalls)
        assertEquals(1, embedding.prewarmCalls)
    }

    @Test
    fun 要約も埋め込みも済んでいれば何も先読みしない() = runBlocking {
        val id = insertBookmark()
        runWorker(id, runAttemptCount = 0)

        runWorker(id, runAttemptCount = 0)

        // 2回目は要約も現行版の埋め込みも済み。1回目の分だけ(端末内モデルで要約したため埋め込みモデルは先読みしていない)
        assertEquals(1, localLlmForPrewarm.prewarmCalls)
        assertEquals(0, embedding.prewarmCalls)
    }

    @Test
    fun フォアグラウンドの通知はAI処理中の低い重要度のチャンネルで_タイトルを出す() = runBlocking {
        val id = db.bookmarkDao().insert(
            Bookmark(type = BookmarkType.TEXT, content = "本文", title = "読みたい記事", createdAt = 1L, lastAccessedAt = 1L)
                .toEntity()
        )

        val info = buildWorker(id).getForegroundInfo()

        assertEquals(AiProcessingNotification.NOTIFICATION_ID, info.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val notification = info.notification
        assertEquals(AiProcessingNotification.CHANNEL_ID, notification.channelId)
        assertEquals(context.getString(R.string.ai_processing_notification_title), notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("要約を作成中: 読みたい記事", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val channel = NotificationManagerCompat.from(context).getNotificationChannelCompat(AiProcessingNotification.CHANNEL_ID)!!
        assertEquals(NotificationManagerCompat.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun フォアグラウンドの通知はブックマークが無くても作れる() = runBlocking {
        val info = buildWorker(bookmarkId = 999L).getForegroundInfo()

        assertEquals("要約を作成中…", info.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun 端末内AIの要約はフォアグラウンドで実行しても完了する() = runBlocking {
        // テスト用のワーカーのフォアグラウンド化は成功する。端末内 AI の設定ではその経路を通る
        val id = insertBookmark()

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.DONE, statusOf(id))
    }

    @Test
    fun フォアグラウンドにするのは時間がかかりそうな要約だけ() {
        // 端末内 AI は短い本文でもフォアグラウンド
        assertTrue(shouldRunInForeground(usesLocalLlm = true, textChars = 10, hasAttachment = false))
        // クラウド API の短い本文は数秒で終わるため通知を出さない
        assertEquals(false, shouldRunInForeground(usesLocalLlm = false, textChars = 10, hasAttachment = false))
        assertEquals(false, shouldRunInForeground(usesLocalLlm = false, textChars = AiPrompts.LONG_TEXT_CHARS, hasAttachment = false))
        // 長い本文・ファイルそのものを渡すときはクラウド API でもフォアグラウンド
        assertTrue(shouldRunInForeground(usesLocalLlm = false, textChars = AiPrompts.LONG_TEXT_CHARS + 1, hasAttachment = false))
        assertTrue(shouldRunInForeground(usesLocalLlm = false, textChars = 10, hasAttachment = true))
    }

    @Test
    fun 段ごとの所要時間をブックマークごとに記録する() = runBlocking {
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 0)

        val stages = AiPerfLog.recent().filter { it.bookmarkId == id }.map { it.stage }
        // 解析 → 埋め込み → 全体の順に記録される
        assertEquals(listOf("analysis", "embedding", "total"), stages)
    }

    @Test
    fun 削除済みのブックマークはAIを呼ばずに終える() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), runWorker(999L, runAttemptCount = 0))
        assertEquals(0, llm.calls)
    }

    @Test
    fun 通信エラーは試行回数が増えても再試行する() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Network()

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 5))
        assertEquals(AiStatus.PENDING, statusOf(id))
    }

    @Test
    fun レート制限は試行回数が増えても再試行する() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.RateLimited(retryAfterSeconds = 60)

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 5))
        assertEquals(AiStatus.PENDING, statusOf(id))
    }

    @Test
    fun 通信エラーでも上限に達したら失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Network()

        // 一括処理のチェーンを止めないよう、ワークとしては成功で終える(状態はブックマークの FAILED で表す)
        assertEquals(
            ListenableWorker.Result.success(),
            runWorker(id, runAttemptCount = maxAttemptsFor(LlmException.Network()) - 1)
        )
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun サーバーエラーは上限に達したら失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.ServerError(503)

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 0))
        assertEquals(
            ListenableWorker.Result.success(),
            runWorker(id, runAttemptCount = maxAttemptsFor(LlmException.ServerError(503)) - 1)
        )
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun 試行回数の上限は通信エラーとレート制限で大きくする() {
        val default = maxAttemptsFor(LlmException.ServerError(503))
        assertTrue(maxAttemptsFor(LlmException.Network()) > default)
        assertTrue(maxAttemptsFor(LlmException.RateLimited()) > default)
        assertEquals(default, maxAttemptsFor(IllegalStateException("boom")))
    }

    @Test
    fun 生成の時間切れは再試行せず失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Timeout(180_000L)

        // 一括処理のチェーンを止めないよう、ワークとしては成功で終える。設定画面から手動で再処理できる
        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun 解析のライブラリを初期化できなくても処理中のまま残さず失敗にし_埋め込みは作る() = runBlocking {
        val id = insertBookmark()
        // release で MediaPipe の静的初期化が失敗したときの Error(LlmProvider で例外に変えていない経路の備え)
        llm.failure = ExceptionInInitializerError(IllegalStateException("no caller found on the stack"))

        // Error でワークを失敗させない(失敗させると表示が「処理中」のまま残り、一括の列の後続も止まる)
        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.FAILED, statusOf(id))
        assertEquals(1, embedding.documents.size)
    }

    @Test
    fun 端末内AIが使えない例外は再試行せず失敗にする() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.EngineUnavailable(NoClassDefFoundError("LlmInference"))

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.FAILED, statusOf(id))
        assertEquals(1, llm.calls)
    }

    @Test
    fun 埋め込みのライブラリを初期化できなくても処理中のまま残さず失敗にする() = runBlocking {
        val id = insertBookmark()
        embedding.failure = NoClassDefFoundError("com.google.mediapipe.framework.Graph")

        assertEquals(ListenableWorker.Result.success(), runWorker(id, runAttemptCount = 0))
        assertEquals(AiStatus.FAILED, statusOf(id))
    }

    @Test
    fun 中断されたら処理中のまま残さず処理待ちに戻す() = runBlocking {
        val id = insertBookmark()
        llm.hang = true

        val work = launch { runWorker(id, runAttemptCount = 0) }
        llm.started.await()
        assertEquals(AiStatus.PROCESSING, statusOf(id))
        assertEquals(ProcessingStage.ANALYZE, stageOf(id))

        // WorkManager による停止(実行時間の上限・取り消しなど)を、コルーチンのキャンセルで再現する
        work.cancelAndJoin()
        assertEquals(AiStatus.PENDING, statusOf(id))
        assertEquals(null, stageOf(id))
    }

    private suspend fun stageOf(id: Long) = repository.getBookmarkById(id)!!.processing.stage

    @Test
    fun 要約_タグ付けの後に埋め込みの段に進み_完了すると段を消して終えた日時を残す() = runBlocking {
        val id = insertBookmark()
        val stagesWhileEmbedding = mutableListOf<Pair<AiStatus, ProcessingStage?>>()
        embedding.onEmbed = { stagesWhileEmbedding += statusOf(id) to stageOf(id) }

        runWorker(id, runAttemptCount = 0)

        assertEquals(listOf(AiStatus.PROCESSING to ProcessingStage.EMBED), stagesWhileEmbedding)
        // やり遂げた段の所要時間を記録する(残り時間の目安に使う)
        assertEquals(
            listOf(ProcessingStage.ANALYZE, ProcessingStage.EMBED),
            stats.recorded.map { it.first.stage }
        )
        assertTrue(stats.recorded.all { it.first.kind == ContentKind.MEMO && it.second >= 0 })
        val processing = repository.getBookmarkById(id)!!.processing
        assertEquals(AiStatus.DONE, statusOf(id))
        assertEquals(null, processing.stage)
        assertTrue(processing.startedAt != null && processing.finishedAt != null)
        assertTrue(processing.finishedAt!! >= processing.startedAt!!)
        assertEquals(null, processing.failureReason)
    }

    @Test
    fun 要約が済んでいれば埋め込みの段だけを通る() = runBlocking {
        val id = insertBookmark()
        repository.updateSummary(id, "既存の要約")
        val stagesWhileEmbedding = mutableListOf<ProcessingStage?>()
        embedding.onEmbed = { stagesWhileEmbedding += stageOf(id) }

        runWorker(id, runAttemptCount = 0)

        assertEquals(0, llm.calls)
        assertEquals(listOf(ProcessingStage.EMBED), stagesWhileEmbedding)
        assertEquals(AiStatus.DONE, statusOf(id))
    }

    @Test
    fun 再試行を待つ間は処理待ちにして失敗の理由を残す() = runBlocking {
        val id = insertBookmark()
        llm.failure = LlmException.Network()

        assertEquals(ListenableWorker.Result.retry(), runWorker(id, runAttemptCount = 0))

        assertEquals(AiStatus.PENDING, statusOf(id))
        assertEquals(null, stageOf(id))
        assertEquals(ProcessingFailureReason.NETWORK, repository.getBookmarkById(id)!!.processing.failureReason)
        // やり遂げていない段の所要時間は記録しない
        assertTrue(stats.recorded.isEmpty())
    }

    @Test
    fun 失敗_準備待ちには理由を残す() = runBlocking {
        val cases = listOf(
            LlmException.Timeout(180_000L) to (AiStatus.FAILED to ProcessingFailureReason.TIMEOUT),
            NothingToAnalyzeException() to (AiStatus.WAITING_MODEL to ProcessingFailureReason.NOTHING_TO_ANALYZE),
            LlmException.Refused() to (AiStatus.FAILED to ProcessingFailureReason.REFUSED)
        )
        for ((failure, expected) in cases) {
            val id = insertBookmark()
            llm.failure = failure

            runWorker(id, runAttemptCount = 0)

            val bookmark = repository.getBookmarkById(id)!!
            assertEquals(failure.javaClass.simpleName, expected, bookmark.aiStatus to bookmark.processing.failureReason)
        }
    }

    @Test
    fun 試行の回数の上限に達したものは理由を残して失敗にする() = runBlocking {
        val id = insertBookmark()

        runWorker(id, runAttemptCount = 1_000)

        val bookmark = repository.getBookmarkById(id)!!
        assertEquals(AiStatus.FAILED, bookmark.aiStatus)
        assertEquals(ProcessingFailureReason.TOO_MANY_ATTEMPTS, bookmark.processing.failureReason)
        assertEquals(0, llm.calls)
    }
}

/** analyze の結果を差し替えられる LLM。渡された既存タグを記録する。 */
private class ScriptedLlmProvider : LlmProvider {
    /** analyze で投げるもの。Error(ライブラリの初期化の失敗)も投げられる */
    var failure: Throwable? = null
    var calls = 0
    /** true なら analyze は取り消されるまで終わらない。 */
    var hang = false
    val started = CompletableDeferred<Unit>()
    var tags: List<String> = listOf("タグ")
    var lastExistingTags: List<String>? = null
    var lastInput: AnalysisInput? = null

    override suspend fun analyze(input: AnalysisInput, existingTags: List<String>, scope: AnalysisScope): BookmarkAnalysis {
        calls++
        lastInput = input
        started.complete(Unit)
        if (hang) awaitCancellation()
        lastExistingTags = existingTags
        failure?.let { throw it }
        return BookmarkAnalysis("要約", tags, "その他")
    }

    override suspend fun chat(userMessage: String, context: List<String>, history: List<ChatMessage>): String =
        error("not used")
}

/** 埋め込みにしたテキストを記録する。 */
private class RecordingEmbeddingProvider : EmbeddingProvider {
    private val delegate = FakeEmbeddingProvider()
    val documents = mutableListOf<String>()
    /** embedDocument で投げるもの */
    var failure: Throwable? = null
    override val profile: EmbeddingModelProfile = delegate.profile
    /** embedDocument の中で呼ぶ(埋め込みを作っている間の状態を調べる) */
    var onEmbed: (suspend () -> Unit)? = null
    override suspend fun embedDocument(text: String): FloatArray {
        onEmbed?.invoke()
        failure?.let { throw it }
        documents += text
        return delegate.embedDocument(text)
    }
    override suspend fun embedQuery(text: String): FloatArray = delegate.embedQuery(text)

    var prewarmCalls = 0
    override fun prewarm() {
        prewarmCalls++
    }
}
