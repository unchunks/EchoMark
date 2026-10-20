package com.unchunks.echomark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.ui.theme.EchoMarkTheme

/**
 * AI 状態の短い文言。バッジ・一覧・詳細など画面をまたいで必ずこれを使い、同じ言い方にする。
 * WAITING_MODEL はローカルモデル未取り込み・API キー未設定のどちらでもなるため、原因を限定しない言い方にする。
 */
fun AiStatus.badgeLabel(): String = when (this) {
    AiStatus.PENDING -> "AI処理待ち"
    AiStatus.PROCESSING -> "AI処理中…"
    AiStatus.DONE -> "AI要約済み"
    AiStatus.FAILED -> "AI処理に失敗"
    AiStatus.WAITING_MODEL -> "AIの準備待ち"
}

/** 詳細画面などで状態の理由・次の行動を伝える説明文。完了(DONE)は説明不要なので null。 */
fun AiStatus.statusDescription(): String? = when (this) {
    AiStatus.PENDING -> "まもなく AI が要約とタグ付けを始めます。"
    AiStatus.PROCESSING -> "AI が内容を読んで、要約とタグを作っています。"
    AiStatus.DONE -> null
    AiStatus.FAILED -> "AI の処理に失敗しました。通信状況や AI の設定を確認して、もう一度お試しください。"
    AiStatus.WAITING_MODEL ->
        "AI を使う準備ができていません。AI 設定で端末内モデルを取り込むか、API キーを設定すると自動で処理します。"
}

/**
 * ブックマークの AI 処理状態を、色・アイコン・短い文言で示すバッジ。
 * 処理中は小さなプログレスをアイコンの代わりに回す。
 */
@Composable
fun AiStatusBadge(
    status: AiStatus,
    modifier: Modifier = Modifier
) {
    val colors = status.badgeColors()
    val label = status.badgeLabel()
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = "AIの状態: $label" },
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.content
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 24.dp)
                .padding(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val icon = status.badgeIcon()
            if (icon == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = colors.content,
                    strokeWidth = 1.5.dp
                )
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private data class BadgeColors(val container: Color, val content: Color)

@Composable
private fun AiStatus.badgeColors(): BadgeColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        AiStatus.PENDING -> BadgeColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        AiStatus.PROCESSING -> BadgeColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        AiStatus.DONE -> BadgeColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        AiStatus.FAILED -> BadgeColors(scheme.errorContainer, scheme.onErrorContainer)
        AiStatus.WAITING_MODEL -> BadgeColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}

/** 処理中はプログレスを出すため null */
private fun AiStatus.badgeIcon(): ImageVector? = when (this) {
    AiStatus.PENDING -> Icons.Outlined.Schedule
    AiStatus.PROCESSING -> null
    AiStatus.DONE -> Icons.Outlined.AutoAwesome
    AiStatus.FAILED -> Icons.Outlined.ErrorOutline
    AiStatus.WAITING_MODEL -> Icons.Outlined.HourglassEmpty
}

@PreviewLightDark
@Composable
private fun AiStatusBadgePreview() {
    EchoMarkTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiStatus.entries.forEach { AiStatusBadge(it) }
            }
        }
    }
}
