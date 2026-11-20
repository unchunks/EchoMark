package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

/**
 * 設定の1行。先頭アイコン・見出し・補足・末尾(値やボタン)を並べる。
 * [onClick] があれば行全体をタップ対象にする。
 */
@Composable
fun SettingsItem(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (titleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                else titleColor.copy(alpha = alpha)
            )
        },
        supportingContent = summary?.let {
            {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
            }
        },
        // 装飾なので読み上げない(意味は見出しで伝える)
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = iconTint.copy(alpha = alpha)) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
        )
    )
}

/** スイッチ付きの設定行。行全体のタップで切り替わり、TalkBack ではスイッチとして読まれる。 */
@Composable
fun SettingsSwitchItem(
    title: String,
    icon: ImageVector?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true
) {
    SettingsItem(
        title = title,
        icon = icon,
        summary = summary,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
    )
}

/**
 * 補足説明のカード(プライバシーの注意、バックアップに含まれないもの など)。
 * [isWarning] なら注意を引く色にする。
 */
@Composable
fun SettingsNotice(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    val container = if (isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (action == null) 12.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = content)
            Text(text, style = MaterialTheme.typography.bodyMedium.japaneseParagraph(), color = content)
        }
        if (action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) { action() }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

/**
 * 日本語の文を文節の区切りで折り返す(「ブックマーク」の途中や「を」だけの行を作らない)。
 * 文節での折り返しは Android 13+ かつ文字列のロケールが日本語のときだけ有効なので、ロケールも明示する
 * (UI は日本語のみ。端末の言語が英語でも同じ折り返しにする)。
 */
internal fun TextStyle.japaneseParagraph(): TextStyle = copy(
    localeList = LocaleList("ja"),
    lineBreak = LineBreak(
        strategy = LineBreak.Strategy.HighQuality,
        strictness = LineBreak.Strictness.Strict,
        wordBreak = LineBreak.WordBreak.Phrase
    )
)
