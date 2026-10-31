package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.ui.components.BookmarkThumbnail

/**
 * 回答の下に出す、引用元の一覧と、引用しなかった資料・資料との照らし合わせの結果。
 * @param ids 回答の文脈に入れた資料の ID(n 番目が引用番号 n)
 * @param highlighted 回答の中で押された引用番号。その引用元を目立たせ、見える位置に出す
 */
@Composable
internal fun AnswerSources(
    review: AnswerReview,
    ids: List<Long>,
    bookmarks: Map<Long, Bookmark>,
    highlighted: Int?,
    onOpenBookmark: (Long) -> Unit
) {
    if (review.sources.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "引用元 ${review.sources.size}件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            review.sources.forEach { source ->
                bookmarks[source.bookmarkId]?.let { bookmark ->
                    CitedSourceCard(
                        source = source,
                        bookmark = bookmark,
                        isHighlighted = source.number == highlighted,
                        onClick = { onOpenBookmark(bookmark.id) }
                    )
                }
            }
        }
    }
    UncitedSources(review.uncitedNumbers, ids, bookmarks, onOpenBookmark)
    AnswerCheck(review)
}

/** 引用元の1件。タイトルと、回答の根拠になった一節を色付きで出す。 */
@Composable
private fun CitedSourceCard(
    source: CitedSource,
    bookmark: Bookmark,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) bringIntoView.bringIntoView()
    }
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView),
        border = if (isHighlighted) BorderStroke(2.dp, colors.primary) else CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceThumbnail(number = source.number, bookmark = bookmark)
                Spacer(Modifier.width(10.dp))
                SourceTitle(bookmark, modifier = Modifier.weight(1f))
            }
            source.quote?.let { quote ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) {
                            append(quote)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph)
                )
            }
        }
    }
}

/** 文脈に入れたが引用しなかった資料。ふだんは畳んでおき、番号は回答の番号のまま出す。 */
@Composable
private fun UncitedSources(
    numbers: List<Int>,
    ids: List<Long>,
    bookmarks: Map<Long, Bookmark>,
    onOpenBookmark: (Long) -> Unit
) {
    val visible = numbers.mapNotNull { number -> ids.getOrNull(number - 1)?.let(bookmarks::get)?.let { number to it } }
    if (visible.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "引用しなかった資料を閉じる" else "引用しなかった資料 ${visible.size}件")
    }
    if (expanded) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            visible.forEach { (number, bookmark) ->
                OutlinedCard(
                    onClick = { onOpenBookmark(bookmark.id) },
                    modifier = Modifier.width(212.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SourceThumbnail(number = number, bookmark = bookmark)
                        Spacer(Modifier.width(10.dp))
                        SourceTitle(bookmark, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceThumbnail(number: Int, bookmark: Bookmark) {
    Box {
        BookmarkThumbnail(bookmark = bookmark, domain = bookmark.chatThumbnailKey(), size = 40.dp)
        CitationBadge(number = number, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun SourceTitle(bookmark: Bookmark, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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

/** 引用した文を資料と照らし合わせた結果。根拠が見つからない文があれば、開いて確かめられるようにする。 */
@Composable
private fun AnswerCheck(review: AnswerReview) {
    val flagged = review.flagged
    val problems = flagged.size + review.unknownNumbers.size
    if (problems == 0) {
        if (review.checkedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "引用した${review.checkedCount}文の根拠を保存内容で確認しました",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${problems}件は根拠を確認できませんでした",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "閉じる" else "確かめる") }
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            flagged.forEach { claim ->
                Column {
                    Text(
                        text = claim.sentence,
                        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph)
                    )
                    Text(
                        text = claimNote(claim),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (review.unknownNumbers.isNotEmpty()) {
                Text(
                    text = "${citationLabel(review.unknownNumbers)} に対応する資料はありません",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "言い換えた文は見つからないことがあります。間違いと決まったわけではありません。",
                style = MaterialTheme.typography.labelSmall.copy(lineBreak = LineBreak.Paragraph),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun citationLabel(numbers: List<Int>): String = numbers.joinToString("") { "[$it]" }

/** 確かめてほしい文の、資料との違いの説明。 */
internal fun claimNote(claim: AnswerClaim): String = when (val better = claim.betterSource) {
    null -> "${citationLabel(claim.cited)} に根拠が見つかりません"
    else -> "${citationLabel(claim.cited)} より [$better] に近い内容です"
}
