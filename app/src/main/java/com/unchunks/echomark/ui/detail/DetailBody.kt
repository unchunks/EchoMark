package com.unchunks.echomark.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.unchunks.components.composeui.formatRelativeTime
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import com.unchunks.echomark.domain.bookmark.model.SearchMatcher
import com.unchunks.echomark.domain.bookmark.model.readingMinutes
import com.unchunks.echomark.domain.extract.ExtractedBody
import com.unchunks.echomark.ui.attachment.rememberAttachmentFile
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.snapshot.PageSnapshotSection
import com.unchunks.echomark.domain.video.isOfflineVideoTarget
import com.unchunks.echomark.ui.video.VideoOfflineSection
import com.unchunks.echomark.ui.places.PlaceSection
import com.unchunks.echomark.ui.snapshot.canSnapshot
import com.unchunks.echomark.ui.components.CompactBookmarkCard
import com.unchunks.echomark.ui.components.rememberHighlighted
import com.unchunks.echomark.ui.navigation.centeredMaxWidth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.unchunks.echomark.ui.design.EchoMarkButton
import com.unchunks.echomark.ui.design.EchoMarkTonalButton

@Composable
internal fun DetailBody(
    bookmark: Bookmark,
    uiState: BookmarkDetailUiState,
    callbacks: BookmarkDetailCallbacks,
    nowMillis: Long,
    onOpenNoteEditor: (BookmarkNote?, String?) -> Unit,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // キーボードの分だけ表示範囲を縮め、タグの入力欄がキーボードに隠れないようにする
            // (スクロールより前に付ける。入力中の欄は表示範囲に収まるよう自動でスクロールされる)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(bottom = 24.dp)
            // 広い画面では中央の読みやすい幅に収める(スクロールより後に付けるので、スクロールは幅全体で受ける)
            .centeredMaxWidth()
    ) {
        val file = rememberAttachmentFile(bookmark)
        val fileType = bookmark.attachmentType()
        val imageUrl = bookmark.imageUrl
        // 動画のオフライン保存で端末に保存したサムネイルがあれば、通信せずに出せるようそちらを使う
        val savedThumbnail = callbacks.videoOfflineState.thumbnailFile
        if (file != null && fileType != null && fileType != BookmarkType.TEXT) {
            AttachmentPreview(
                file,
                fileType,
                onOpenFile = { callbacks.onOpenFile(bookmark) },
                onPlayVideo = { callbacks.onPlayAttachmentVideo(bookmark) }
            )
            Spacer(Modifier.height(16.dp))
        } else if (savedThumbnail != null || !imageUrl.isNullOrBlank()) {
            // 装飾扱い(内容はタイトルで伝わる)。OG 画像の標準比率 1.91:1 で切り出す
            AsyncImage(
                model = savedThumbnail ?: imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.91f)
                    .clip(MaterialTheme.shapes.large)
            )
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SourceAndDate(bookmark, nowMillis)
            Spacer(Modifier.height(6.dp))
            Text(
                text = rememberHighlighted(bookmark.title, uiState.highlightTerms),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            // ファイルの種類のブックマークは、ファイルが無くても(バックアップから読み込んだときなど)その旨を出す
            if (bookmark.filePath != null || bookmark.type in FILE_TYPES) {
                Spacer(Modifier.height(12.dp))
                FileInfoCard(bookmark, file, onOpenFile = { callbacks.onOpenFile(bookmark) })
            }
            Spacer(Modifier.height(16.dp))
            PrimaryActions(bookmark, hasFile = file != null, callbacks = callbacks)
            // 続けて開けなかったと判断したリンクだけに出す(1回の失敗では出さない)
            uiState.linkState?.takeIf { it.isDead && !it.muted }?.let { linkState ->
                Spacer(Modifier.height(16.dp))
                DeadLinkSection(
                    state = linkState,
                    hasOfflineCopy = bookmark.hasSnapshot,
                    checking = uiState.linkChecking,
                    onOpenOfflineCopy = callbacks.snapshot.onOpen,
                    onSearchArchive = { bookmark.contentUri?.let { callbacks.onOpenInBrowser(archiveSearchUrl(it)) } },
                    onRecheck = callbacks.onRecheckLink,
                    onMute = callbacks.onMuteLink
                )
            }
            if (bookmark.isOfflineVideoTarget()) {
                // 動画サイトのページは、ページのコピーではなく字幕・要約・サムネイルを保存する(動画そのものは保存しない)
                Spacer(Modifier.height(16.dp))
                VideoOfflineSection(bookmark = bookmark, state = callbacks.videoOfflineState, callbacks = callbacks.videoOffline)
            }
            // http / https のリンクだけに出す(それ以外では何も描かない)。動画サイトのページでは、前に作ったコピーがあるときだけ
            // (開く・消すため)
            if (canSnapshot(bookmark.contentUri) && (!bookmark.isOfflineVideoTarget() || bookmark.hasSnapshot)) {
                Spacer(Modifier.height(16.dp))
                PageSnapshotSection(bookmark = bookmark, callbacks = callbacks.snapshot, busy = callbacks.snapshotBusy)
            }
            Spacer(Modifier.height(20.dp))
            AiSection(bookmark, uiState.stageProgress, uiState.stageAverages, uiState.summaryHistory, uiState.translation, uiState.categoryChoices, callbacks, uiState.highlightTerms)
            Spacer(Modifier.height(24.dp))
            TagSection(
                tags = bookmark.tags,
                aiTags = bookmark.aiTags,
                allTagNames = uiState.allTagNames,
                onAddTag = callbacks.onAddTag,
                onRemoveTag = callbacks.onRemoveTag
            )
            Spacer(Modifier.height(24.dp))
            NotesSection(
                notes = uiState.notes,
                nowMillis = nowMillis,
                onAdd = { onOpenNoteEditor(null, null) },
                onEdit = { onOpenNoteEditor(it, null) },
                onDelete = callbacks.onDeleteNote
            )
            Spacer(Modifier.height(24.dp))
            PlaceSection(bookmark = bookmark, callbacks = callbacks.place)
            val content = bookmark.content
            if (!content.isNullOrBlank()) {
                // ファイルから取り出した文字(OCR・文字起こしなど)は区切り行で本文に入っているので、メモと分けて見出しを付ける
                val parts = ExtractedBody.parse(content)
                val sections = if (parts.sections.isEmpty()) {
                    listOf(contentLabel(bookmark) to content)
                } else {
                    listOfNotNull(parts.memo?.let { "メモ" to it }) + parts.sections.map { it.source.label to it.text }
                }
                // 検索語が出てくる最初の節は、開いて、その場所まで画面を動かす
                val firstMatching = remember(sections, uiState.highlightTerms) {
                    if (uiState.highlightTerms.isEmpty()) -1
                    else sections.indexOfFirst { SearchMatcher.ranges(it.second, uiState.highlightTerms).isNotEmpty() }
                }
                sections.forEachIndexed { index, (label, text) ->
                    key(index) {
                        Spacer(Modifier.height(24.dp))
                        ContentSection(label = label, text = text, highlightTerms = uiState.highlightTerms, scrollToMatch = index == firstMatching)
                    }
                }
            }
        }

        if (uiState.related.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            DetailSectionTitle("関連するブックマーク", Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.related, key = { it.id }) { related ->
                    CompactBookmarkCard(bookmark = related, onClick = { callbacks.onOpenBookmark(related.id) })
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "保存: ${formatDateTime(bookmark.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
}

@Composable
private fun SourceAndDate(bookmark: Bookmark, nowMillis: Long) {
    val domain = bookmark.contentUri?.let { extractDomain(it) }
    val source = listOfNotNull(bookmark.siteName?.takeIf { it.isNotBlank() }, domain)
        .distinct()
        .joinToString(" · ")
        .ifEmpty { bookmark.attachmentType()?.displayName() ?: bookmark.type.displayName() }
    Text(
        text = source,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val readingMinutes = remember(bookmark.content) { bookmark.readingMinutes() }
        Text(
            text = "${formatRelativeTime(bookmark.createdAt, nowMillis)}に保存" +
                (readingMinutes?.let { " · ${ReadingTime.label(it)}で読めます" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (bookmark.isArchived) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    "アーカイブ済み",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PrimaryActions(bookmark: Bookmark, hasFile: Boolean, callbacks: BookmarkDetailCallbacks) {
    val url = bookmark.contentUri
    AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
        if (url != null) {
            EchoMarkButton(onClick = { callbacks.onOpenInBrowser(url) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("ブラウザで開く", textAlign = TextAlign.Center)
            }
        } else if (hasFile) {
            EchoMarkButton(onClick = { callbacks.onOpenFile(bookmark) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("ファイルを開く", textAlign = TextAlign.Center)
            }
        }
        EchoMarkTonalButton(onClick = { callbacks.onAskAi(bookmark.id) }) {
            Icon(Icons.Outlined.QuestionAnswer, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("AIに質問", textAlign = TextAlign.Center)
        }
    }
}

/**
 * ボタンを同じ幅で横に並べる。文字サイズを大きくしているなどで、どれかの文字が等分の幅に収まらないときは、
 * 文字を切らずに幅いっぱいのボタンを縦に積む。
 */
@Composable
private fun AdaptiveButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val spacingPx = spacing.roundToPx()
        val width = constraints.maxWidth
        val count = measurables.size
        val cellWidth = (width - spacingPx * (count - 1)) / count
        val fitsInRow = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= cellWidth }
        if (fitsInRow) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(cellWidth)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (cellWidth + spacingPx), (height - placeable.height) / 2)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + spacingPx * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + spacingPx
                }
            }
        }
    }
}

/** 本文・メモ。長いときは6行で畳み、「もっと見る」で開く */
@Composable
private fun ContentSection(label: String, text: String, highlightTerms: List<String> = emptyList(), scrollToMatch: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    val firstMatch = remember(text, highlightTerms) { SearchMatcher.ranges(text, highlightTerms).firstOrNull()?.first }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var scrolled by remember { mutableStateOf(false) }
    // 検索結果から開いたときは、一致した文字が見えるよう全文を開く
    LaunchedEffect(firstMatch) { if (firstMatch != null) expanded = true }
    // 全文が組み上がったら、最初に一致した場所まで画面を動かす(1回だけ)
    LaunchedEffect(layout, expanded) {
        val result = layout
        if (scrollToMatch && !scrolled && firstMatch != null && expanded && result != null &&
            result.layoutInput.maxLines == Int.MAX_VALUE && firstMatch < result.layoutInput.text.length
        ) {
            scrolled = true
            requester.bringIntoView(result.getBoundingBox(firstMatch))
        }
    }
    DetailSectionTitle(label)
    Spacer(Modifier.height(8.dp))
    Text(
        text = rememberHighlighted(text, highlightTerms),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = {
            layout = it
            if (!expanded) overflowing = it.hasVisualOverflow
        },
        modifier = Modifier.bringIntoViewRequester(requester).animateContentSize()
    )
    if (overflowing || expanded) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
            Text(if (expanded) "折りたたむ" else "もっと見る")
        }
    }
}

@Composable
internal fun DetailSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() }
    )
}

private const val COLLAPSED_LINES = 6

/** ファイルを保存する種類 */
private val FILE_TYPES = setOf(BookmarkType.IMAGE, BookmarkType.PDF, BookmarkType.AUDIO, BookmarkType.VIDEO)

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d H:mm")

private fun formatDateTime(millis: Long): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
