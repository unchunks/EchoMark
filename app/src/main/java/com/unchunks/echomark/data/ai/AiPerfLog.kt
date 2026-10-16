package com.unchunks.echomark.data.ai

import timber.log.Timber
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * AI 処理の段ごとの所要時間の計測(どこで時間がかかっているかを調べる用)。
 * - Timber の `AiPerf` タグでログに出す(debug ビルドだけ Logcat に出る。`adb logcat -s AiPerf`)
 * - 直近 [CAPACITY] 件はメモリにも残し、[recent]・[summaryOf] で読める(デバッグ画面などで使える。永続化はしない)
 *
 * 段の名前(目安): `fetch`(本文取得)、`extract`(中身の取り出し)、`analysis`(要約・タグの全体)、
 * `llm.engineLoad`(端末内モデルの読み込み)、`llm.generate`(端末内モデルの1回の生成。部分要約 `part 2/4` と最終 `final`)、
 * `embedding`、`total`(AI 処理ワーカー全体)。
 *
 * ブックマークごとに集計できるよう、ワーカーは [trace] を付けたコンテキストで処理する
 * (内側のサスペンド関数・コルーチンにも引き継がれ、[record]・[measure] が拾う)。
 */
object AiPerfLog {
    const val TAG = "AiPerf"

    /** メモリに残す件数。古いものから捨てる。 */
    const val CAPACITY = 200

    /**
     * 計測した1件。
     * @property atMillis 記録した時刻(epoch millis。段の終わり)
     * @property bookmarkId どのブックマークの処理か。分からなければ null
     * @property durationMs 段の所要時間
     */
    data class Entry(
        val atMillis: Long,
        val bookmarkId: Long?,
        val stage: String,
        val durationMs: Long,
        val detail: String
    )

    /** コルーチンのコンテキストに載せ、計測をブックマークに結びつける目印。 */
    class Trace(val bookmarkId: Long) : AbstractCoroutineContextElement(Trace) {
        companion object Key : CoroutineContext.Key<Trace>
    }

    fun trace(bookmarkId: Long): CoroutineContext = Trace(bookmarkId)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    /** 現在時刻(epoch millis)。テストで差し替える。 */
    @Volatile
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    /** 1件を記録してログに出す。どのブックマークの処理か分からなければ [bookmarkId] は null のまま。 */
    fun record(stage: String, durationMs: Long, detail: String = "", bookmarkId: Long? = null) {
        val entry = Entry(wallClock(), bookmarkId, stage, durationMs, detail)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
        }
        Timber.tag(TAG).i(format(entry))
    }

    /**
     * [block] の所要時間を [stage] として記録して、結果を返す(失敗・キャンセルでも、そこまでの時間を記録する)。
     * コンテキストに [Trace] があれば、そのブックマークの計測にする。
     */
    suspend inline fun <T> measure(stage: String, detail: String = "", block: () -> T): T {
        val bookmarkId = coroutineContext[Trace]?.bookmarkId
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(stage, (System.nanoTime() - start) / NANOS_PER_MILLI, detail, bookmarkId)
        }
    }

    /** 直近の記録(古い順)。 */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    /**
     * [bookmarkId] の直近 [windowMs] の記録を段ごとに合計した1行の要約。
     * 例: `bookmark#12 total=45.2s [fetch=1.2s analysis=40.1s llm.generate=38.9s(x4) embedding=1.1s]`
     * ([total] 自身は先頭に出す)。記録が無ければ null。
     */
    fun summaryOf(bookmarkId: Long, windowMs: Long = SUMMARY_WINDOW_MS): String? {
        val since = wallClock() - windowMs
        val mine = recent().filter { it.bookmarkId == bookmarkId && it.atMillis >= since }
        if (mine.isEmpty()) return null
        val byStage = LinkedHashMap<String, Pair<Long, Int>>()
        for (entry in mine) {
            val (sum, count) = byStage[entry.stage] ?: (0L to 0)
            byStage[entry.stage] = (sum + entry.durationMs) to (count + 1)
        }
        val total = byStage["total"]
        val parts = byStage.filterKeys { it != "total" }.entries.joinToString(" ") { (stage, value) ->
            val (sum, count) = value
            "$stage=${seconds(sum)}" + if (count > 1) "(x$count)" else ""
        }
        return buildString {
            append("bookmark#$bookmarkId")
            if (total != null) append(" total=${seconds(total.first)}")
            append(" [$parts]")
        }
    }

    private fun format(entry: Entry): String = buildString {
        entry.bookmarkId?.let { append("bookmark#$it ") }
        append(entry.stage).append(" = ").append(seconds(entry.durationMs))
        if (entry.detail.isNotEmpty()) append(" (").append(entry.detail).append(')')
    }

    private fun seconds(ms: Long): String = "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)

    @PublishedApi
    internal const val NANOS_PER_MILLI = 1_000_000L

    private const val SUMMARY_WINDOW_MS = 30 * 60 * 1000L
}
