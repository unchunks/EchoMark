package com.unchunks.echomark.ui.shortcut

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.navigation.Routes

/**
 * ホーム画面に置くショートカット1つの中身([PinnedShortcuts] が作る)。
 * @param id ショートカットの ID。同じ ID で置き直すと、ランチャーが古いものを更新する
 * @param uri 開く先(アプリのディープリンク)
 */
data class PinnedShortcutSpec(val id: String, val shortLabel: String, val longLabel: String, val uri: String)

/**
 * ブックマーク・コレクションを、ホーム画面のショートカットとして置く(利用者が詳細画面などで選んだとき)。
 * 開く先は通知・ウィジェットと同じディープリンク(`echomark://bookmark/{id}`・`echomark://collection/{id}`)。
 * 置くときの確認はシステムが出す(アプリが勝手に置くことはできない)。
 */
object PinnedShortcuts {
    /** ランチャーが一覧に出す短い名前の長さの目安(これより長いと切れる) */
    const val SHORT_LABEL_MAX = 10

    /** 長い名前の長さの目安 */
    const val LONG_LABEL_MAX = 25

    private const val FALLBACK_NAME = "ブックマーク"

    fun forBookmark(bookmark: Bookmark): PinnedShortcutSpec {
        val name = bookmark.title.trim().ifEmpty { FALLBACK_NAME }
        return PinnedShortcutSpec(
            id = "bookmark_${bookmark.id}",
            shortLabel = name.ellipsized(SHORT_LABEL_MAX),
            longLabel = name.ellipsized(LONG_LABEL_MAX),
            uri = Routes.bookmarkDeepLink(bookmark.id)
        )
    }

    fun forCollection(id: Long, name: String): PinnedShortcutSpec {
        val clean = name.trim().ifEmpty { "コレクション" }
        return PinnedShortcutSpec(
            id = "collection_$id",
            shortLabel = clean.ellipsized(SHORT_LABEL_MAX),
            longLabel = "コレクション「${clean.ellipsized(LONG_LABEL_MAX - 7)}」",
            uri = Routes.collectionDeepLink(id)
        )
    }

    /** [max] 文字までにする(超えるときは最後を「…」にして [max] 文字に収める)。 */
    internal fun String.ellipsized(max: Int): String =
        when {
            length <= max -> this
            max <= 1 -> "…"
            else -> take(max - 1) + "…"
        }

    /**
     * [spec] をホーム画面に置くようシステムに頼む(確認の画面はシステムが出す)。
     * ランチャーが対応していなければ、その旨を出す。
     */
    fun request(context: Context, spec: PinnedShortcutSpec) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "このホーム画面にはショートカットを置けません", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, spec.uri.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val info = ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_bookmark))
            .setIntent(intent)
            .build()
        val requested = ShortcutManagerCompat.requestPinShortcut(context, info, null)
        if (!requested) Toast.makeText(context, "ショートカットを置けませんでした", Toast.LENGTH_SHORT).show()
    }
}
