package com.unchunks.echomark.ui.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.ui.components.displaySource
import java.util.concurrent.TimeUnit

/** ウィジェットの1行分。タップで詳細を開く。 */
data class RediscoverWidgetItem(
    val bookmarkId: Long,
    val title: String,
    /** サイト名・ドメイン(URL 以外は「メモ」などの種類名) */
    val source: String
)

/**
 * ウィジェットの大きさに応じた出し方。
 * @param maxItems 出す件数(小: 1件、中: 2件、大: 3件)
 * @param showTitle ヘッダーに「再発見」の文字を出すか(狭いとボタンに押されて切れるため、出さない)
 * @param showSource 各行にサイト名・ドメインを出すか(1件だけの小さいサイズでは高さが足りないため、タイトルだけ)
 */
data class RediscoverWidgetLayout(val maxItems: Int, val showTitle: Boolean, val showSource: Boolean)

/**
 * 「EchoMark 再発見」ウィジェットに出す内容を決める純粋な処理。
 * 一覧上部の「今日の再発見」(BookmarkViewModel)と同じ顔ぶれになるよう、同じ手順で選ぶ。
 */
object RediscoverWidgetData {

    /** 小さいサイズ(1件)。ウィジェットの最小サイズ */
    val SMALL_SIZE = DpSize(180.dp, 110.dp)

    /** 中くらいのサイズ(2件)。既定の 3×2 マスはだいたいここ */
    val MEDIUM_SIZE = DpSize(180.dp, 180.dp)

    /** 大きいサイズ(3件) */
    val LARGE_SIZE = DpSize(180.dp, 250.dp)

    /** この幅以上なら、ヘッダーに「再発見」の文字を出す */
    private val TITLE_MIN_WIDTH = 240.dp

    /** Glance の SizeMode.Responsive に渡す大きさ。高さ3段階 × 幅2段階(文字を出す・出さない) */
    val RESPONSIVE_SIZES: Set<DpSize> = listOf(SMALL_SIZE, MEDIUM_SIZE, LARGE_SIZE)
        .flatMap { listOf(it, it.copy(width = TITLE_MIN_WIDTH)) }
        .toSet()

    /** 一覧の「今日の再発見」と同じ候補数(BookmarkViewModel.REDISCOVER_CANDIDATES) */
    private const val CANDIDATE_LIMIT = 30

    /**
     * 全ブックマークから、今日の再発見を最大3件選ぶ。
     * 一覧と同じく「[RediscoverSelector.IN_APP_STALE_DAYS] 日以上開いていないもの」を古い順に [CANDIDATE_LIMIT] 件取り、
     * [RediscoverSelector.pickDaily] で日替わりに選ぶ(アーカイブ済みは除く)。
     */
    fun select(bookmarks: List<Bookmark>, now: Long): List<Bookmark> {
        val threshold = now - TimeUnit.DAYS.toMillis(RediscoverSelector.IN_APP_STALE_DAYS)
        val candidates = bookmarks
            .filter { it.lastAccessedAt <= threshold }
            .sortedWith(compareBy<Bookmark> { it.lastAccessedAt }.thenBy { it.id })
            .take(CANDIDATE_LIMIT)
        return RediscoverSelector.pickDaily(candidates, now = now, dayIndex = TimeUnit.MILLISECONDS.toDays(now))
    }

    /** 表示用の行に変換する。 */
    fun toItems(bookmarks: List<Bookmark>): List<RediscoverWidgetItem> =
        bookmarks.map { RediscoverWidgetItem(it.id, it.title, it.displaySource()) }

    /** ウィジェットの大きさから出し方を決める(件数は高さ、ヘッダーの文字は幅で決める)。 */
    fun layoutFor(size: DpSize): RediscoverWidgetLayout {
        val maxItems = when {
            size.height >= LARGE_SIZE.height -> RediscoverSelector.MAX_ITEMS
            size.height >= MEDIUM_SIZE.height -> 2
            else -> 1
        }
        return RediscoverWidgetLayout(
            maxItems = maxItems,
            showTitle = size.width >= TITLE_MIN_WIDTH,
            showSource = maxItems > 1
        )
    }
}
