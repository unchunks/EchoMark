package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** 生成中の末尾に出すカーソル。 */
private const val CURSOR = "▍"

/**
 * [ChatMarkdown] で解析した回答を表示する。
 * 引用番号は小さなバッジで表示し、[isCitationAvailable] が true のものはタップで [onCitationClick] を呼ぶ。
 * @param cursorAlpha 非 null なら末尾に点滅カーソルを出す(生成中)。値は不透明度
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    // 行頭に「ー」や小書きの仮名、句読点が来ないよう、厳格な禁則で折り返す
    style: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
    color: Color = LocalContentColor.current,
    isCitationAvailable: (Int) -> Boolean = { false },
    onCitationClick: (Int) -> Unit = {},
    cursorAlpha: Float? = null
) {
    val blocks = remember(markdown) { ChatMarkdown.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val inlineStyles = InlineStyles(
        code = SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 0.9.em,
            background = colors.surfaceContainerHighest
        ),
        citation = SpanStyle(
            color = colors.onPrimaryContainer,
            background = colors.primaryContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = 0.8.em
        ),
        disabledCitation = SpanStyle(
            color = colors.onSurfaceVariant,
            background = colors.surfaceContainerHighest,
            fontSize = 0.8.em
        ),
        cursor = cursorAlpha?.let { SpanStyle(color = colors.primary.copy(alpha = it)) }
    )

    Column(modifier = modifier) {
        if (blocks.isEmpty() && inlineStyles.cursor != null) {
            Text(buildAnnotatedString { withStyle(inlineStyles.cursor) { append(CURSOR) } }, style = style)
        }
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val tight = block is ChatMarkdown.Block.ListItem && blocks[index - 1] is ChatMarkdown.Block.ListItem
                Spacer(Modifier.height(if (tight) 4.dp else 10.dp))
            }
            val isLast = index == blocks.lastIndex
            val cursor = if (isLast) inlineStyles.cursor else null
            when (block) {
                is ChatMarkdown.Block.Heading -> Text(
                    text = block.spans.toAnnotated(inlineStyles, isCitationAvailable, onCitationClick, cursor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.Bold),
                    color = color,
                    modifier = Modifier.semantics { heading() }
                )
                is ChatMarkdown.Block.Paragraph -> Text(
                    text = block.spans.toAnnotated(inlineStyles, isCitationAvailable, onCitationClick, cursor),
                    style = style,
                    color = color
                )
                is ChatMarkdown.Block.ListItem -> Row(modifier = Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = if (block.ordered) color else colors.primary,
                        modifier = Modifier.widthIn(min = 20.dp).padding(end = 4.dp)
                    )
                    Text(
                        text = block.spans.toAnnotated(inlineStyles, isCitationAvailable, onCitationClick, cursor),
                        style = style,
                        color = color
                    )
                }
                is ChatMarkdown.Block.Code -> CodeBlock(block, cursor)
                ChatMarkdown.Block.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun CodeBlock(block: ChatMarkdown.Block.Code, cursor: SpanStyle?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            block.language?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            // 長い行は折り返さず横にスクロールする(コードの桁を崩さない)
            Text(
                text = buildAnnotatedString {
                    append(block.code)
                    if (cursor != null) withStyle(cursor) { append(CURSOR) }
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

private class InlineStyles(
    val code: SpanStyle,
    val citation: SpanStyle,
    val disabledCitation: SpanStyle,
    val cursor: SpanStyle?
)

private fun List<ChatMarkdown.Span>.toAnnotated(
    styles: InlineStyles,
    isCitationAvailable: (Int) -> Boolean,
    onCitationClick: (Int) -> Unit,
    cursor: SpanStyle?
): AnnotatedString = buildAnnotatedString {
    forEachIndexed { index, span ->
        val decoration = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null
        )
        val number = span.citation
        // [2][3] のように続く引用番号は、バッジがくっついて「23」に見えないよう間を空ける
        if (number != null && getOrNull(index - 1)?.citation != null) append(" ")
        when {
            number != null && isCitationAvailable(number) -> {
                // 数字の前後に細いスペースを入れ、背景色でバッジに見せる
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "citation:$number",
                        styles = TextLinkStyles(style = styles.citation)
                    ) { onCitationClick(number) }
                ) { append(" $number ") }
            }
            number != null -> withStyle(styles.disabledCitation) { append(" $number ") }
            span.code -> withStyle(styles.code.merge(decoration)) { append(" ${span.text} ") }
            else -> withStyle(decoration) { append(span.text) }
        }
    }
    if (cursor != null) withStyle(cursor) { append(CURSOR) }
}
