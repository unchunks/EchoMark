package com.unchunks.echomark.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.unchunks.components.composeui.MarkdownText
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ConversationBranch
import com.unchunks.echomark.ui.components.BookmarkThumbnail

/**
 * 長押しでコピーできるようにする(TalkBack ではカスタムアクション「コピー」)。
 * 呼び出し側は描き直すたびに新しいラムダを渡すため、ラムダをキーにすると(ストリーミング中など)長押しの途中で
 * ジェスチャーの検出がやり直しになる。検出は続けたまま、最新のラムダを呼ぶ。
 */
@Composable
private fun Modifier.copyOnLongPress(onCopy: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val currentOnCopy by rememberUpdatedState(onCopy)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnCopy()
            })
        }
        .semantics { customActions = listOf(CustomAccessibilityAction("コピー") { onCopy(); true }) }
}

/**
 * ユーザーの発言。右寄せの吹き出し(右下だけ角を小さくして、話し手の側を示す)。
 * [onEdit] を渡すと(最後の質問)、吹き出しの左に編集ボタンを出す。
 * [onBranch] を渡すと(途中の質問)、そこから会話を分けて質問を直すボタンを出す。
 */
@Composable
internal fun UserMessageItem(
    text: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom
    ) {
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "質問を編集",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else if (onBranch != null) {
            IconButton(onClick = onBranch) {
                Icon(
                    Icons.AutoMirrored.Outlined.CallSplit,
                    contentDescription = "ここから分けて質問を直す",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
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
 * アシスタントの回答。Markdown を整形して表示し、下に引用元とコピーボタンを出す。
 * 引用番号 [n] は [ChatMessage.referencedBookmarkIds] の n 番目(保存順)に対応する。削除済みは押せない。
 * 引用番号を押すと、引用元の一覧のその資料を目立たせる。資料を開くのは引用元のカードから。
 * @param review 引用元と資料との照らし合わせの結果。確かめ終わるまでは null で、引用元を出さない
 */
@Composable
internal fun AssistantMessageItem(
    message: ChatMessage,
    referencedBookmarks: Map<Long, Bookmark>,
    onOpenBookmark: (Long) -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    review: AnswerReview? = null,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onSaveNote: (() -> Unit)? = null,
    isSpeaking: Boolean = false,
    onToggleSpeaking: (() -> Unit)? = null
) {
    val ids = message.referencedBookmarkIds
    var highlighted by rememberSaveable(message.id) { mutableStateOf<Int?>(null) }
    AssistantRow(modifier = modifier) {
        MarkdownText(
            markdown = message.content,
            isCitationAvailable = { n -> ids.getOrNull(n - 1)?.let { it in referencedBookmarks } == true },
            onCitationClick = { n -> highlighted = n.takeIf { it != highlighted } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .copyOnLongPress(onCopy)
        )
        review?.let {
            AnswerSources(
                review = it,
                ids = ids,
                bookmarks = referencedBookmarks,
                highlighted = highlighted,
                onOpenBookmark = onOpenBookmark
            )
        }
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
            if (onRegenerate != null) {
                IconButton(onClick = onRegenerate) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "回答を作り直す",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (onBranch != null) {
                IconButton(onClick = onBranch) {
                    Icon(
                        Icons.AutoMirrored.Outlined.CallSplit,
                        contentDescription = "ここから会話を分ける",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (onSaveNote != null) {
                IconButton(onClick = onSaveNote) {
                    Icon(
                        Icons.AutoMirrored.Outlined.NoteAdd,
                        contentDescription = "回答をメモに保存",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (onToggleSpeaking != null) {
                IconButton(onClick = onToggleSpeaking) {
                    Icon(
                        if (isSpeaking) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = if (isSpeaking) "読み上げを止める" else "回答を読み上げる",
                        tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
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
    modifier: Modifier = Modifier,
    onResumePrevious: (() -> Unit)? = null
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
        if (onResumePrevious != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onResumePrevious) { Text("前の会話の続きを開く") }
        }
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

/** この発言から分けた会話へのリンク。 */
@Composable
internal fun BranchLinks(
    branches: List<ConversationBranch>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        branches.forEach { branch ->
            TextButton(onClick = { onOpen(branch.id) }) {
                Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(branch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 分けた会話の上部に出す、元の会話へのリンク。 */
@Composable
internal fun BranchParentBar(parent: ConversationBranch, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onOpen, modifier = modifier) {
        Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("「${parent.title}」から分けた会話", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 回答の下に出す、次の質問の候補。押すとそのまま送る。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowUpSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 回答の本文の左端にそろえる(アイコン 32dp + 間 12dp)
    FlowRow(
        modifier = modifier.padding(start = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { suggestion ->
            SuggestionChip(
                onClick = { onClick(suggestion) },
                label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
