package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.provider.LlmException
import timber.log.Timber

/**
 * 長い本文の分割要約の設定。
 * @property chunkChars 1回で AI に渡す本文の上限。これ以下の本文は分けずに1回で要約する
 * @property maxChunks 部分要約する部分の数の上限(呼び出し回数・時間・料金の上限になる)。超える分は均等に間引く
 * @property noteMaxChars 部分ごとの要約メモの文字数の目安
 * @property timeBudgetMillis 部分要約にかける時間の目安。超えたら残りの部分は飛ばして、まとめに進む
 */
data class LongTextConfig(
    val chunkChars: Int,
    val maxChunks: Int,
    val noteMaxChars: Int,
    val timeBudgetMillis: Long
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
 * - 本文が [LongTextConfig.chunkChars] 以下なら AI を呼ばずにそのまま返す(従来どおり1回で要約)
 * - 部分の数が [LongTextConfig.maxChunks] を超えるときは、先頭と末尾を含めて均等に選ぶ
 * - 部分要約の生成の時間切れ([LlmException.Timeout])はその部分を飛ばす。それ以外の失敗はそのまま投げる(再試行させる)
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
        if (text.length <= config.chunkChars) return PreparedBody.Whole(text)
        val chunks = TextChunker.split(text, config.chunkChars)
        if (chunks.size <= 1) return PreparedBody.Whole(chunks.firstOrNull() ?: text)
        val start = clock()
        val notes = mutableListOf<Pair<Int, String>>()
        for (index in TextChunker.selectEvenly(chunks.size, config.maxChunks)) {
            if (index != 0 && clock() - start > config.timeBudgetMillis) {
                Timber.i("部分要約の時間の目安を超えたため、残りの部分を飛ばす: %d/%d", notes.size, chunks.size)
                break
            }
            val note = try {
                summarizePart(TextPart(index + 1, chunks.size, chunks[index]))
            } catch (e: LlmException.Timeout) {
                Timber.w("部分要約が時間切れのため飛ばす: %d/%d", index + 1, chunks.size)
                continue
            }
            // 指示より長く書かれても、まとめの入力に収まるようにする
            note.trim().take(config.noteMaxChars * NOTE_SLACK_PERCENT / 100)
                .takeIf { it.isNotEmpty() }
                ?.let { notes += (index + 1) to it }
        }
        if (notes.isEmpty()) return PreparedBody.Whole(text)
        return PreparedBody.Digest(AiPrompts.digestBody(notes, chunks.size), notes.size, chunks.size)
    }

    private companion object {
        /** 部分要約のメモを、指示した文字数の何 % まで使うか */
        const val NOTE_SLACK_PERCENT = 130
    }
}
