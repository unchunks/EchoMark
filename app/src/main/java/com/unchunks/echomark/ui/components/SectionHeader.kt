package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import com.unchunks.echomark.ui.theme.LocalDesignStyle

/**
 * リストや詳細画面のセクション見出し。右端に「すべて見る」などのアクションを置ける。
 * 左右の余白は一覧の内容に合わせて 16dp。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = sectionHeaderStyle(),
            color = sectionHeaderColor(),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 見出しの文字。デザインごとに見出しの声色を変える。画面ごとに見出しを作るときも、これと [sectionHeaderColor] を使う。
 * - ペーパー: 明朝体(titleMedium)。紙面の小見出し
 * - クリア: 小さめの灰色の文字(iPhone の設定のまとまりの見出し)
 * - そのほか: アクセント色の titleSmall
 */
@Composable
@ReadOnlyComposable
fun sectionHeaderStyle(): TextStyle = when (LocalDesignStyle.current) {
    DesignStyle.PAPER -> MaterialTheme.typography.titleMedium
    DesignStyle.CLEAR -> MaterialTheme.typography.labelLarge
    else -> MaterialTheme.typography.titleSmall
}

@Composable
@ReadOnlyComposable
fun sectionHeaderColor(): Color =
    if (LocalDesignStyle.current == DesignStyle.CLEAR) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary

@PreviewLightDark
@Composable
private fun SectionHeaderPreview() {
    EchoMarkTheme {
        Surface {
            Column {
                SectionHeader("関連するブックマーク")
                SectionHeader("最近保存したもの", actionLabel = "すべて見る", onAction = {})
            }
        }
    }
}
