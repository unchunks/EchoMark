package com.unchunks.echomark.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.ui.components.BookmarkThumbnail

/** 長押しでコピーできるようにする(TalkBack ではカスタムアクション「コピー」)。 */
@Composable
private fun Modifier.copyOnLongPress(onCopy: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return this
        .pointerInput(onCopy) {
            detectTapGestures(onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCopy()
            })
        }
        .semantics { customActions = listOf(CustomAccessibilityAction("コピー") { onCopy(); true }) }
}

/** ユーザーの発言。右寄せの吹き出し(右下だけ角を小さくして、話し手の側を示す)。 */
@Composable
internal fun UserMessageItem(text: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Spacer(Modifier.width(48.dp))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .copyOnLongPress(onCopy)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

/** アシスタントのアイコン。 */
@Composable
internal fun AssistantAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** アシスタントの行の骨組み。左にアイコン、右に本文(幅いっぱい)。 */
@Composable
private fun AssistantRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AssistantAvatar(modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * アシスタントの回答。Markdown を整形して表示し、下に参照したブックマークとコピーボタンを出す。
 * 引用番号 [n] は [ChatMessage.referencedBookmarkIds] の n 番目(保存順)に対応する。削除済みは押せない。
 */
@Composable
internal fun AssistantMessageItem(
    message: ChatMessage,
    referencedBookmarks: Map<Long, Bookmark>,
    onOpenBookmark: (Long) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ids = message.referencedBookmarkIds
    AssistantRow(modifier = modifier) {
        MarkdownText(
            markdown = message.content,
            isCitationAvailable = { n -> ids.getOrNull(n - 1)?.let { it in referencedBookmarks } == true },
            onCitationClick = { n -> ids.getOrNull(n - 1)?.let(onOpenBookmark) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .copyOnLongPress(onCopy)
        )
        ReferenceCards(ids = ids, bookmarks = referencedBookmarks, onOpenBookmark = onOpenBookmark)
        // アイコンの左端を本文にそろえる(IconButton の内側の余白ぶん左へずらす)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = (-12).dp)) {
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "回答をコピー",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 参照したブックマークの小さなカード(横スクロール)。削除済みは出さないが、番号は回答中の [n] と揃える。
 */
@Composable
private fun ReferenceCards(
    ids: List<Long>,
    bookmarks: Map<Long, Bookmark>,
    onOpenBookmark: (Long) -> Unit
) {
    val visible = ids.withIndex().mapNotNull { (index, id) -> bookmarks[id]?.let { index + 1 to it } }
    if (visible.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        text = "参照したブックマーク",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visible.forEach { (number, bookmark) ->
            ReferenceCard(number = number, bookmark = bookmark, onClick = { onOpenBookmark(bookmark.id) })
        }
    }
}

@Composable
private fun ReferenceCard(number: Int, bookmark: Bookmark, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.width(212.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                BookmarkThumbnail(bookmark = bookmark, domain = bookmark.chatThumbnailKey(), size = 40.dp)
                CitationBadge(number = number, modifier = Modifier.align(Alignment.TopStart))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookmark.chatSourceLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CitationBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * 生成中の回答。文字が届くまでは進み具合(検索中 → 作成中)を出し、届いたら点滅カーソル付きで伸ばしていく。
 * 保存前なので引用カードは出さない。
 */
@Composable
internal fun StreamingMessageItem(
    text: String,
    pendingReferenceCount: Int?,
    modifier: Modifier = Modifier
) {
    AssistantRow(modifier = modifier) {
        if (text.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = progressLabel(pendingReferenceCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val transition = rememberInfiniteTransition(label = "cursor")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.15f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 530), RepeatMode.Reverse),
                label = "cursorAlpha"
            )
            MarkdownText(
                markdown = text,
                cursorAlpha = alpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

internal fun progressLabel(pendingReferenceCount: Int?): String = when {
    pendingReferenceCount == null -> "関連するブックマークを探しています…"
    pendingReferenceCount == 0 -> "回答を作成しています…"
    else -> "${pendingReferenceCount}件のブックマークをもとに回答を作成しています…"
}

/** 送信の失敗。原因と、再試行・AI 設定への導線を出す。 */
@Composable
internal fun ChatErrorItem(
    error: ChatError,
    onRetry: () -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showAiSettingsButton: Boolean = error.needsAiSettings
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("回答を作成できませんでした", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(error.message, style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (showAiSettingsButton) {
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("再試行")
                    }
                    Button(onClick = onOpenAiSettings) {
                        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("AI 設定を開く")
                    }
                } else {
                    Button(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("再試行")
                    }
                }
            }
        }
    }
}

/**
 * 空の会話の挨拶。何ができるかの説明と、タップで送れる質問の例を出す。
 * ブックマークについて質問するときは、そのブックマーク向けの文言にする。
 */
@Composable
internal fun ChatWelcome(
    isAboutBookmark: Boolean,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isAboutBookmark) Icons.Outlined.AutoAwesome else Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isAboutBookmark) "このブックマークについて質問できます" else "保存した知識に質問してみましょう",
            style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // 中央寄せの説明は、意味の切れ目で改行して行の長さをそろえる
            text = if (isAboutBookmark) {
                "要約や要点の整理、関連する保存の紹介など\nこのブックマークをもとに AI が答えます"
            } else {
                "保存したブックマークをもとに AI が答えます\n参照したブックマークは番号で示されます"
            },
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "質問の例",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionItem(text = suggestion, onClick = { onSuggestionClick(suggestion) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionItem(text: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 画面上部に出す「このブックマークについて質問中」のカード。タップで詳細を開く。 */
@Composable
internal fun AboutBookmarkBar(
    bookmark: Bookmark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BookmarkThumbnail(bookmark = bookmark, domain = bookmark.chatThumbnailKey(), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "このブックマークについて質問中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = bookmark.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bookmark.chatSourceLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
