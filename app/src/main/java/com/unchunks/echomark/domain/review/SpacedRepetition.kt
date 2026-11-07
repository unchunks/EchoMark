package com.unchunks.echomark.domain.review

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 復習の答え方(思い出せたか)。 */
enum class ReviewGrade {
    /** 思い出せなかった。同じ日のうちにもう一度出す */
    AGAIN,

    /** 思い出せたが、むずかしかった */
    HARD,

    /** 思い出せた */
    GOOD,

    /** すぐ思い出せた */
    EASY
}

/**
 * 復習に入れたブックマークの状態(#90)。DB の `review_cards` に対応する。
 * @param easePermille 間隔の伸びやすさ(1000 で 1.0 倍。[SpacedRepetition.DEFAULT_EASE] から始まる)
 * @param intervalDays 今の間隔(日)。0 は「もう一度」を選んだ直後
 */
data class ReviewCard(
    val bookmarkId: Long,
    val dueAt: Long,
    val intervalDays: Int,
    val easePermille: Int,
    val repetitions: Int,
    val lapses: Int,
    val lastReviewedAt: Long?,
    val createdAt: Long
) {
    fun isDue(now: Long): Boolean = dueAt <= now
}

/**
 * 間隔をあけて思い出す復習(SM-2 を簡単にしたもの)の計算。純粋な処理。
 *
 * - 続けて思い出せるほど間隔を伸ばす(1 日 → 3 日 → 前の間隔 × 伸びやすさ…)。伸びやすさは「かんたん」で上がり、
 *   「むずかしい」「もう一度」で下がる(下限 [MIN_EASE])。間隔の上限は [MAX_INTERVAL_DAYS] 日
 * - 「もう一度」は間隔を 0 に戻して [RELEARN_MINUTES] 分後にまた出す(続けて思い出せた回数も 0 に戻る)
 * - 次の復習の日時は、日付の区切り(その地域の 0 時)にそろえる。夜に復習したものが、翌日の昼まで出ないことのないように
 */
object SpacedRepetition {
    const val DEFAULT_EASE = 2500
    const val MIN_EASE = 1300
    const val MAX_INTERVAL_DAYS = 365

    /** 「もう一度」を選んだものをまた出すまでの分数 */
    const val RELEARN_MINUTES = 10

    /** 一度に復習する件数の既定(多すぎて続かないのを防ぐ) */
    const val SESSION_LIMIT = 20

    private const val HARD_MULTIPLIER = 1.2
    private const val EASY_BONUS = 1.3
    private const val EASE_UP = 150
    private const val EASE_DOWN_HARD = 150
    private const val EASE_DOWN_AGAIN = 200
    private const val MINUTE_MILLIS = 60_000L

    /** 復習に入れた直後の状態。最初の復習は、入れた翌日([now] の翌日の 0 時)から。 */
    fun newCard(bookmarkId: Long, now: Long, zone: ZoneId): ReviewCard = ReviewCard(
        bookmarkId = bookmarkId,
        dueAt = startOfDay(now, zone, plusDays = 1),
        intervalDays = 0,
        easePermille = DEFAULT_EASE,
        repetitions = 0,
        lapses = 0,
        lastReviewedAt = null,
        createdAt = now
    )

    /** [card] に [grade] で答えたあとの状態。 */
    fun next(card: ReviewCard, grade: ReviewGrade, now: Long, zone: ZoneId): ReviewCard {
        if (grade == ReviewGrade.AGAIN) {
            return card.copy(
                dueAt = now + RELEARN_MINUTES * MINUTE_MILLIS,
                intervalDays = 0,
                easePermille = max(MIN_EASE, card.easePermille - EASE_DOWN_AGAIN),
                repetitions = 0,
                lapses = card.lapses + 1,
                lastReviewedAt = now
            )
        }
        val previous = card.intervalDays
        val ease = card.easePermille / 1000.0
        val interval = when (grade) {
            ReviewGrade.HARD -> max(previous + 1, (max(previous, 1) * HARD_MULTIPLIER).roundToInt())
            ReviewGrade.GOOD -> when (card.repetitions) {
                0 -> 1
                1 -> 3
                else -> max(previous + 1, (previous * ease).roundToInt())
            }
            ReviewGrade.EASY -> when (card.repetitions) {
                0 -> 4
                1 -> 7
                else -> max(previous + 2, (previous * ease * EASY_BONUS).roundToInt())
            }
            ReviewGrade.AGAIN -> 0
        }.coerceIn(1, MAX_INTERVAL_DAYS)
        val newEase = when (grade) {
            ReviewGrade.HARD -> max(MIN_EASE, card.easePermille - EASE_DOWN_HARD)
            ReviewGrade.EASY -> card.easePermille + EASE_UP
            else -> card.easePermille
        }
        return card.copy(
            dueAt = startOfDay(now, zone, plusDays = interval),
            intervalDays = interval,
            easePermille = min(newEase, MAX_EASE),
            repetitions = card.repetitions + 1,
            lastReviewedAt = now
        )
    }

    /** 日時が来ているもの(早い順)。 */
    fun due(cards: List<ReviewCard>, now: Long): List<ReviewCard> =
        cards.filter { it.isDue(now) }.sortedWith(compareBy({ it.dueAt }, { it.bookmarkId }))

    /**
     * 次の復習がいつかの言い方(一覧に出す)。日時が来ていれば「いま復習できます」、同じ日なら「今日あとで」、
     * それ以降は日付の差で「明日」「3日後」「約2か月後」。
     */
    fun dueLabel(dueAt: Long, now: Long, zone: ZoneId): String {
        if (dueAt <= now) return "いま復習できます"
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, due)
        return when {
            days <= 0 -> "今日あとで"
            days == 1L -> "明日"
            days <= 30 -> "${days}日後"
            else -> "約${(days / 30.0).roundToInt()}か月後"
        }
    }

    /** 「次は何日後か」の言い方(答えるボタンの下に出す)。0 日は「もう一度」の再出題。 */
    fun intervalLabel(card: ReviewCard): String = when (card.intervalDays) {
        0 -> "${RELEARN_MINUTES}分後"
        1 -> "明日"
        in 2..30 -> "${card.intervalDays}日後"
        in 31..364 -> "約${(card.intervalDays / 30.0).roundToInt()}か月後"
        else -> "約1年後"
    }

    private const val MAX_EASE = 3500

    private fun startOfDay(now: Long, zone: ZoneId, plusDays: Int): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(plusDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
}
