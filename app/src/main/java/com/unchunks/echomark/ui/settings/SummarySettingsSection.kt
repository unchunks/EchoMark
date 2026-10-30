package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.ui.components.SectionHeader

/*
 * 設定画面の「要約」の欄(#12 要約の言語を固定する、#16 要約の書き方を選ぶ)。
 */

/** 「要約」の欄の行。値を選ぶ行は [onOpenDialog] でダイアログを開く。 */
internal fun LazyListScope.summarySettingsItems(
    style: SummaryStyle,
    onOpenDialog: (SettingsDialog) -> Unit
) {
    item { SectionHeader("要約") }
    item {
        SettingsItem(
            title = "要約の言語",
            icon = Icons.Outlined.Translate,
            summary = "${style.language.label}(本文が別の言語でも、この言語で書きます)",
            onClick = { onOpenDialog(SettingsDialog.SUMMARY_LANGUAGE) }
        )
    }
    item {
        SettingsItem(
            title = "要約の書き方",
            icon = Icons.Outlined.FormatListBulleted,
            summary = "${style.format.label}(${style.format.description})",
            onClick = { onOpenDialog(SettingsDialog.SUMMARY_FORMAT) }
        )
    }
    item {
        Text(
            "変えても、保存済みの要約はそのままです。新しく作る要約から反映され、詳細画面の「再処理」で作り直せます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/** 「要約」の欄から開くダイアログ。該当しなければ何も出さない。 */
@Composable
internal fun SummarySettingsDialog(
    dialog: SettingsDialog,
    style: SummaryStyle,
    onChange: ((SummaryStyle) -> SummaryStyle) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        SettingsDialog.SUMMARY_LANGUAGE -> ChoiceDialog(
            title = "要約の言語",
            options = SummaryLanguage.entries,
            selected = style.language,
            label = { it.label },
            onSelect = { language ->
                onChange { it.copy(language = language) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        SettingsDialog.SUMMARY_FORMAT -> ChoiceDialog(
            title = "要約の書き方",
            options = SummaryFormat.entries,
            selected = style.format,
            label = { "${it.label} ― ${it.description}" },
            onSelect = { format ->
                onChange { it.copy(format = format) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}
