package com.unchunks.echomark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * 空状態。大きめのアイコン(響きを表す同心円つき)・タイトル・説明・次の行動ボタンを中央に並べる。
 * [actionLabel] と [onAction] の両方があるときだけボタンを出す。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: ImageVector? = null
) {
    StateLayout(
        modifier = modifier,
        illustration = {
            EchoIllustration(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ringColor = MaterialTheme.colorScheme.primary
            )
        },
        title = title,
        description = description
    ) {
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(actionLabel)
            }
        }
    }
}

/** 読み込み中。中央にインジケーターと任意のメッセージを出す。 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * エラー状態。何が起きたか([title])と原因・対処([message])を示し、[onRetry] があれば再試行ボタンを出す。
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "読み込めませんでした",
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "再試行"
) {
    StateLayout(
        modifier = modifier,
        illustration = {
            EchoIllustration(
                icon = icon,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ringColor = MaterialTheme.colorScheme.error
            )
        },
        title = title,
        description = message
    ) {
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(retryLabel)
            }
        }
    }
}

@Composable
private fun StateLayout(
    modifier: Modifier,
    illustration: @Composable () -> Unit,
    title: String,
    description: String?,
    action: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            illustration()
            Spacer(Modifier.height(20.dp))
            // 中央寄せの短文は、文節で折り返して行の長さをそろえる(1文字だけの行を作らない)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** アイコンの周りに薄い同心円を描き、「響き返ってくる」イメージを出す装飾。 */
@Composable
private fun EchoIllustration(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    ringColor: Color
) {
    Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.5.dp.toPx()
            drawCircle(ringColor.copy(alpha = 0.12f), radius = size.minDimension / 2 - strokeWidth, style = Stroke(strokeWidth))
            drawCircle(ringColor.copy(alpha = 0.24f), radius = size.minDimension * 0.4f, style = Stroke(strokeWidth))
        }
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // 装飾なので読み上げない(意味はタイトルで伝える)
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(48.dp))
        }
    }
}

@PreviewLightDark
@Composable
private fun EmptyStatePreview() {
    EchoMarkTheme {
        Surface {
            EmptyState(
                icon = Icons.Outlined.BookmarkAdd,
                title = "まだブックマークがありません",
                description = "ブラウザの共有メニューから EchoMark を選ぶと、記事を保存して AI が要約します。",
                actionLabel = "URL を追加",
                onAction = {}
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ErrorStatePreview() {
    EchoMarkTheme {
        Surface {
            ErrorState(message = "ネットワークに接続できませんでした。接続を確認してもう一度お試しください。", onRetry = {})
        }
    }
}

@PreviewLightDark
@Composable
private fun LoadingStatePreview() {
    EchoMarkTheme {
        Surface { LoadingState(message = "読み込み中…") }
    }
}
