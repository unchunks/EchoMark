package com.unchunks.echomark.ui.settings

import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.design.DesignCard
import com.unchunks.echomark.domain.repository.DesignStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.unchunks.echomark.ui.design.AccentCard
import com.unchunks.echomark.ui.design.DesignSurface
import com.unchunks.echomark.ui.design.EchoMarkSwitch
import com.unchunks.echomark.ui.design.SurfaceRole
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
            if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
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
        trailing = { EchoMarkSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
    )
}

@Composable
internal fun SettingsAiCard(ai: AiSummary, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AccentCard(
        containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text("AI の設定", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
            Text(
                if (ai.isReady) "準備完了" else "設定が必要",
                style = MaterialTheme.typography.titleSmall
            )
            Text(ai.label, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
        }
    }
}

/**
 * 補足説明や回復手順を関連する設定の近くに表示する。
 * [isWarning] のテキストは、表示されたときに読み上げる。
 */
@Composable
fun SettingsNotice(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val noticeModifier = modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
    val body: @Composable () -> Unit = {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (action == null) 12.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(icon, contentDescription = null)
                Text(
                    text,
                    modifier = if (isWarning) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
            }
            if (action != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) { action() }
            }
        }
    }
    if (isWarning) {
        // 警告はどのデザインでも警告の色で目立たせる
        Surface(
            color = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = noticeModifier,
            content = body
        )
    } else {
        // ふつうのお知らせは「中の区画」として描く(ソフトならくぼみ、クリアなら灰色の区画)
        DesignSurface(
            classic = colors.surfaceContainerLow,
            role = SurfaceRole.Nested,
            contentColor = colors.onSurfaceVariant,
            modifier = noticeModifier,
            content = body
        )
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

/**
 * 設定の行と、その行に付く操作(ボタン・状態の説明)のひとまとまり。まとまりの一覧([settingsList])の外で
 * 行を置く画面(AI の設定など、カードと行が混ざる画面)で使う。
 * - クラシック: 入れ物なし(これまでどおり、行と操作を並べるだけ)
 * - そのほか: その画面のほかのカードと同じく、デザインのカードの描き方にする(左右 16dp・上下 6dp の余白)
 */
@Composable
internal fun SettingsBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (EchoMarkDesign.style == DesignStyle.CLASSIC) {
        Column(modifier, content = content)
    } else {
        DesignCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            classic = Color.Transparent
        ) {
            content()
            // 最後のボタンがカードの下端に付かないよう、少し空ける
            Spacer(Modifier.height(8.dp))
        }
    }
}
