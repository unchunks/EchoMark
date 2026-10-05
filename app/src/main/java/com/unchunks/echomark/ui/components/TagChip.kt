package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * タグを「#名前」で表示する小さなチップ。
 * [onClick] を渡すとタップでき、タップ領域は 48dp を確保する(見た目の大きさは変えない)。
 * [isAi] なら AI が付けたタグとして先頭に小さな ✨ を付ける(ユーザーが付けたタグと見分けられるように)。
 * 絞り込み用の選択式チップには M3 の FilterChip を使うこと。
 */
@Composable
fun TagChip(
    name: String,
    modifier: Modifier = Modifier,
    isAi: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val label = @Composable {
        Row(
            modifier = Modifier.padding(start = if (isAi) 6.dp else 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAi) {
                AiTagIcon(size = 12.dp, modifier = Modifier.padding(end = 2.dp))
            }
            Text(
                text = "#$name",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    val description = if (isAi) "$AI_TAG_DESCRIPTION $name" else "タグ $name"
    val a11y = Modifier.semantics { contentDescription = description }
    if (onClick == null) {
        Surface(
            modifier = modifier.then(a11y),
            shape = MaterialTheme.shapes.small,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        ) { label() }
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier
                .minimumInteractiveComponentSize()
                .then(a11y),
            shape = MaterialTheme.shapes.small,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        ) { label() }
    }
}

/**
 * AI が付けたタグの印(✨)。説明は周りの要素(チップ・行)でまとめて読み上げるため、ここでは読み上げない。
 * 単独で置くときは [describe] を true にして「AIが付けたタグ」と読み上げさせる。
 */
@Composable
fun AiTagIcon(size: Dp, modifier: Modifier = Modifier, describe: Boolean = false) {
    Icon(
        Icons.Outlined.AutoAwesome,
        contentDescription = if (describe) AI_TAG_DESCRIPTION else null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(size)
    )
}

/** AI が付けたタグの読み上げ用の説明 */
const val AI_TAG_DESCRIPTION = "AIが付けたタグ"

@PreviewLightDark
@Composable
private fun TagChipPreview() {
    EchoMarkTheme {
        Surface {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip("Android")
                TagChip("パフォーマンス", isAi = true)
                TagChip("クリック可能", onClick = {})
            }
        }
    }
}
