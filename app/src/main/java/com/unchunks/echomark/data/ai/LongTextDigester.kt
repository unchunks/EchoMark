package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 長い本文の分割要約の設定。
 * @property chunkChars 分けるときの1部分の長さの上限
 * @property maxChunks 部分要約する部分の数の上限(呼び出し回数・時間・料金の上限になる)。超える分は均等に間引く
 * @property noteMaxChars 部分ごとの要約メモの文字数の目安
 * @property timeBudgetMillis 部分要約にかける時間の目安。超えたら、まだ始めていない部分は飛ばして、まとめに進む
 * @property singleCallChars 分けずに1回で要約できる本文の上限。これ以下の本文は AI を呼ばずにそのまま返す。
 *   既定は [chunkChars](端末内のモデルは文脈長が小さく、1回に渡せる長さと分ける長さが同じ)。
 *   文脈長の大きいクラウド API は、分ける長さより大きくして、たいていの本文を1回で要約する
 * @property parallelism 部分要約を同時に進める数。既定の 1 は順番に1つずつ(端末内のモデルは同時に動かせない)
 */
data class LongTextConfig(
    val chunkChars: Int,
    val maxChunks: Int,
    val noteMaxChars: Int,
    val timeBudgetMillis: Long,
    val singleCallChars: Int = chunkChars,
    val parallelism: Int = 1
)

/** AI に渡す本文。 */
sealed interface PreparedBody {
    val text: String

    /** 元の本文のまま(1回で渡せる長さ。部分要約に失敗したときは長いこともあり、呼び出し側で切り詰める) */
    data class Whole(override val text: String) : PreparedBody

    /** 部分ごとの要約メモをまとめたもの([AiPrompts.digestBody])。[usedParts] / [totalParts] 個の部分から作った */
    data class Digest(override val text: String, val usedParts: Int, val totalParts: Int) : PreparedBody
}

/**
 * 長い本文を部分に分けて要約し(map)、最終の要約に渡すメモにまとめる(reduce の入力)。
 * 最終の要約(JSON)は呼び出し側が [PreparedBody.text] を本文として作る。
 *
 * - 本文が [LongTextConfig.singleCallChars] 以下なら AI を呼ばずにそのまま返す(1回で要約)
 * - 部分の数が [LongTextConfig.maxChunks] を超えるときは、先頭と末尾を含めて均等に選ぶ
 * - 部分要約の生成の時間切れ([LlmException.Timeout])はその部分を飛ばす。それ以外の失敗はそのまま投げる(再試行させる)
 * - [LongTextConfig.parallelism] が 2 以上なら、その数まで部分要約を同時に進める。メモは部分の順に並べ、
 *   時間の目安は「まだ始めていない部分を飛ばす」かどうかの判断に使う(順番に進めるときと同じ)
 * - どの部分の要約も得られなければ元の本文を返す(呼び出し側で先頭を切り出して従来どおり要約する)
 *
 * @param clock 経過時間を測る時計(ミリ秒)。テストで差し替える
 */
class LongTextDigester(
    private val config: LongTextConfig,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /**
     * @param summarizePart 1つの部分の要約メモを作る。空文字なら要点なしとして使わない
     */
    suspend fun prepare(text: String, summarizePart: suspend (TextPart) -> String): PreparedBody {
        if (text.length <= config.singleCallChars) return PreparedBody.Whole(text)
        val chunks = TextChunker.split(text, config.chunkChars)
        if (chunks.size <= 1) return PreparedBody.Whole(chunks.firstOrNull() ?: text)
        val start = clock()
        val indices = TextChunker.selectEvenly(chunks.size, config.maxChunks)
        val results = if (config.parallelism <= 1) {
            indices.map { index -> noteOf(index, chunks, start, summarizePart) }
        } else {
            summarizeInParallel(indices, chunks, start, summarizePart)
        }
        val notes = results.filterNotNull()
        if (notes.isEmpty()) return PreparedBody.Whole(text)
        return PreparedBody.Digest(AiPrompts.digestBody(notes, chunks.size), notes.size, chunks.size)
    }

    /**
     * [index] の部分のメモ((部分の番号, メモ))。飛ばした部分・要点の無い部分は null。
     * 時間の目安を超えていたら要約を始めずに飛ばす(先頭の部分は必ず要約する)。
     */
    private suspend fun noteOf(
        index: Int,
        chunks: List<String>,
        start: Long,
        summarizePart: suspend (TextPart) -> String
    ): Pair<Int, String>? {
        if (index != 0 && clock() - start > config.timeBudgetMillis) {
            Timber.i("部分要約の時間の目安を超えたため、この部分を飛ばす: %d/%d", index + 1, chunks.size)
            return null
        }
        val note = try {
            summarizePart(TextPart(index + 1, chunks.size, chunks[index]))
        } catch (e: LlmException.Timeout) {
            Timber.w("部分要約が時間切れのため飛ばす: %d/%d", index + 1, chunks.size)
            return null
        }
        // 指示より長く書かれても、まとめの入力に収まるようにする
        return note.trim().take(config.noteMaxChars * NOTE_SLACK_PERCENT / 100)
            .takeIf { it.isNotEmpty() }
            ?.let { (index + 1) to it }
    }

    /**
     * [indices] の部分を最大 [LongTextConfig.parallelism] 個ずつ同時に要約する。
     * 部分は先頭から順に取り出して始めるため、時間の目安を超えたときに飛ばされるのは後ろの部分になる。
     * どれかが失敗したら残りもキャンセルして、その失敗を投げる。結果は [indices] と同じ順。
     */
    private suspend fun summarizeInParallel(
        indices: List<Int>,
        chunks: List<String>,
        start: Long,
        summarizePart: suspend (TextPart) -> String
    ): List<Pair<Int, String>?> {
        val results = arrayOfNulls<Pair<Int, String>>(indices.size)
        val queue = Channel<Int>(Channel.UNLIMITED)
        indices.indices.forEach { queue.trySend(it) }
        queue.close()
        // coroutineScope は子がすべて終わってから戻る
        coroutineScope {
            repeat(minOf(config.parallelism, indices.size)) {
                launch {
                    for (position in queue) {
                        results[position] = noteOf(indices[position], chunks, start, summarizePart)
                    }
                }
            }
        }
        return results.toList()
    }

    private companion object {
        /** 部分要約のメモを、指示した文字数の何 % まで使うか */
        const val NOTE_SLACK_PERCENT = 130
    }
}
