package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.settings.japaneseParagraph

/*
 * AI 設定画面のセクションで共通に使う部品。
 */

/** ラジオボタンの行。行全体をタップ対象にし、RadioButton 自体は onClick = null(TalkBack で二重に読まれない)。 */
@Composable
internal fun RadioItem(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: ImageVector? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(description, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailingIcon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    )
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
}

@Composable
internal fun StatusLine(icon: @Composable () -> Unit, text: String, isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.japaneseParagraph(),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
