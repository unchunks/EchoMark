package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.ui.components.AiStatusBadge
import com.unchunks.echomark.ui.components.BookmarkCard
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.components.TagChip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 共通 UI 部品(ui/components)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun bookmarkCards() = screenshot.captureLightDark("bookmark_cards") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // OG 画像あり・お気に入り・タグ4つ(+1 表示)・星ボタンあり
            BookmarkCard(
                bookmark = PreviewSamples.urlBookmark,
                onClick = {},
                onToggleFavorite = {},
                nowMillis = PreviewSamples.NOW
            )
            // 画像なし(ドメイン頭文字)・タイトルが URL のまま・AI 処理中
            BookmarkCard(bookmark = PreviewSamples.processingBookmark, onClick = {}, nowMillis = PreviewSamples.NOW)
            // テキストメモ(種類アイコン)・AI 失敗
            BookmarkCard(
                bookmark = PreviewSamples.textBookmark,
                onClick = {},
                onToggleFavorite = {},
                nowMillis = PreviewSamples.NOW
            )
            // PDF・モデル待ち・星ボタンなしでお気に入りマーク
            BookmarkCard(
                bookmark = PreviewSamples.waitingModelBookmark.copy(isFavorite = true),
                onClick = {},
                nowMillis = PreviewSamples.NOW
            )
        }
    }

    @Test
    fun aiStatusBadges() = screenshot.captureLightDark("ai_status_badges") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AiStatus.entries.forEach { AiStatusBadge(it) }
        }
    }

    @Test
    fun emptyState() = screenshot.captureLightDark("empty_state") {
        Box(Modifier.height(480.dp)) {
            EmptyState(
                icon = Icons.Outlined.BookmarkAdd,
                title = "まだブックマークがありません",
                description = "ブラウザの共有メニューから EchoMark を選ぶと、記事を保存して AI が要約します。",
                actionLabel = "URL を追加",
                onAction = {},
                actionIcon = Icons.Outlined.Add
            )
        }
    }

    @Test
    fun errorAndLoading() = screenshot.captureLightDark("error_loading_state") {
        Column {
            Box(Modifier.height(420.dp)) {
                ErrorState(
                    message = "ネットワークに接続できませんでした。接続を確認してもう一度お試しください。",
                    onRetry = {}
                )
            }
            Box(Modifier.height(160.dp)) {
                LoadingState(message = "読み込み中…")
            }
        }
    }

    @Test
    fun headerAndTags() = screenshot.captureLightDark("section_header_tags") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("関連するブックマーク")
            SectionHeader("最近保存したもの", actionLabel = "すべて見る", onAction = {})
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TagChip("Android")
                TagChip("AIが付けたタグ", isAi = true)
                TagChip("とても長いタグの名前はどう表示されるか")
                TagChip("タップできるタグ", onClick = {})
            }
        }
    }
}
