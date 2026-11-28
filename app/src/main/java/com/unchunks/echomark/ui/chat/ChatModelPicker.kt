package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.ChatModelChoices
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.ui.settings.modelDisplayName
import com.unchunks.echomark.ui.settings.shortName

private val ChatModelOption.displayLabel: String
    get() = when (this) {
        is ChatModelOption.Local -> modelDisplayName(fileName)
        is ChatModelOption.Cloud -> this.label
    }

private val ChatModelOption.sectionTitle: String
    get() = when (this) {
        is ChatModelOption.Local -> "端末内"
        is ChatModelOption.Cloud -> provider.shortName
    }

/**
 * トップバーに出す、次の質問に使う AI の表示と切り替えのメニュー。選べる AI が無ければ何も出さない
 * (上部の案内から AI 設定へ進む)。
 * @param enabled 生成中は切り替えられない
 */
@Composable
internal fun ChatModelPicker(
    choices: ChatModelChoices,
    enabled: Boolean,
    onSelect: (ChatModelOption) -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (choices.options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val current = choices.selected?.displayLabel ?: "AI を選ぶ"
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
            modifier = Modifier
                .widthIn(max = MAX_BUTTON_WIDTH)
                .semantics { contentDescription = "使う AI を選ぶ。選択中: $current" }
        ) {
            Text(
                text = current,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChatModelMenuItems(
                choices = choices,
                onSelect = { expanded = false; onSelect(it) },
                onOpenAiSettings = { expanded = false; onOpenAiSettings() }
            )
        }
    }
}

/** メニューの中身。端末内・提供元ごとにまとめ、1種類だけなら見出しは出さない。 */
@Composable
internal fun ColumnScope.ChatModelMenuItems(
    choices: ChatModelChoices,
    onSelect: (ChatModelOption) -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val sections = choices.options.groupBy { it.sectionTitle }
    sections.forEach { (title, options) ->
        if (sections.size > 1) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        options.forEach { option ->
            val isSelected = option == choices.selected
            DropdownMenuItem(
                text = { Text(option.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onSelect(option) },
                trailingIcon = if (isSelected) ({ Icon(Icons.Outlined.Check, contentDescription = "選択中") }) else null
            )
        }
    }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("AI 設定を開く") },
        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
        onClick = onOpenAiSettings
    )
}

/** 長いファイル名でも、トップバーの会話の名前を残す幅 */
private val MAX_BUTTON_WIDTH = 132.dp
