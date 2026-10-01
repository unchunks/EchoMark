package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * タグを「#名前」で表示する小さなチップ。
 * [onClick] を渡すとタップでき、タップ領域は 48dp を確保する(見た目の大きさは変えない)。
 * 絞り込み用の選択式チップには M3 の FilterChip を使うこと。
 */
@Composable
fun TagChip(
    name: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val label = @Composable {
        Text(
            text = "#$name",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
    val a11y = Modifier.semantics { contentDescription = "タグ $name" }
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

@PreviewLightDark
@Composable
private fun TagChipPreview() {
    EchoMarkTheme {
        Surface {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagChip("Android")
                TagChip("パフォーマンス")
                TagChip("クリック可能", onClick = {})
            }
        }
    }
}
