package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.SearchMatch
import com.unchunks.echomark.domain.bookmark.model.SearchMatcher

/** 検索語に一致した文字に付ける強調(背景と太字)。文字の色も合わせ、明るい・暗いどちらの配色でも読める */
@Composable
fun searchHighlightStyle(): SpanStyle = SpanStyle(
    background = MaterialTheme.colorScheme.primaryContainer,
    color = MaterialTheme.colorScheme.onPrimaryContainer,
    fontWeight = FontWeight.Bold
)

/** [ranges](終端を含む)に [style] を付けた文字列を作る。範囲が文字列の外にはみ出していても、収まる分だけ付ける */
fun highlightedText(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString =
    if (ranges.isEmpty()) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text)
            for (range in ranges) {
                val start = range.first.coerceAtLeast(0)
                val end = (range.last + 1).coerceAtMost(text.length)
                if (start < end) addStyle(style, start, end)
            }
        }
    }

/** [terms] に一致する文字を強調した [text]。語が無ければそのままの文字列 */
@Composable
fun rememberHighlighted(text: String, terms: List<String>): AnnotatedString {
    val style = searchHighlightStyle()
    return remember(text, terms, style) {
        if (terms.isEmpty()) AnnotatedString(text) else highlightedText(text, SearchMatcher.ranges(text, terms), style)
    }
}

/**
 * 検索結果のカードの下に出す、一致した理由(#54)。「一致: タイトル・本文」と、本文に一致した場所の前後。
 * キーワードには一致せず意味の近さだけで見つかったもの(別の目印が付く)には何も出さない。
 */
@Composable
fun SearchMatchReason(match: SearchMatch, modifier: Modifier = Modifier) {
    if (match.isSemanticOnly) return
    val style = searchHighlightStyle()
    Column(modifier) {
        Text(
            text = "一致: ${match.reason}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        match.snippet?.let { snippet ->
            val text = remember(snippet, style) { highlightedText(snippet.text, snippet.ranges, style) }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
