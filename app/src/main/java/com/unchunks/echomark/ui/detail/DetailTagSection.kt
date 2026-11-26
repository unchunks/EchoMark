package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.components.AiTagIcon
import com.unchunks.echomark.ui.design.EchoMarkSuggestionChip

/**
 * タグ: × で外し(取り消せる)、入力欄で追加(既存タグを候補に出す)。
 * AI が付けたタグ([aiTags])には印を付ける。AI のタグは再処理で付け直されるが、自分で付けたタグはそのまま残る
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagSection(
    tags: List<String>,
    aiTags: Set<String>,
    allTagNames: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(input, allTagNames, tags) { tagSuggestions(input, allTagNames, tags) }
    val submit = {
        if (input.isNotBlank()) {
            onAddTag(input)
            input = ""
        }
    }

    DetailSectionTitle("タグ")
    Spacer(Modifier.height(8.dp))
    if (tags.isEmpty()) {
        Text(
            "タグはまだありません。下の欄から追加できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                RemovableTagChip(name = tag, isAi = tag in aiTags, onRemove = { onRemoveTag(tag) })
            }
        }
        if (tags.any { it in aiTags }) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AiTagIcon(size = 14.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    "AIが付けたタグ(再処理で付け直されます)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("タグを追加") },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = submit, enabled = input.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = "タグを追加")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() })
    )
    if (suggestions.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { name ->
                EchoMarkSuggestionChip(
                    onClick = {
                        onAddTag(name)
                        input = ""
                    },
                    label = { Text("#$name", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

/**
 * 付いているタグ。外せるのは × だけにする(チップ本体を押しても外れない。誤って触れて消えるのを防ぐ)。
 * × は見た目 32dp だが、タッチは周囲を含めて 48dp まで受け付ける(Compose の最小タッチ領域)。
 * [isAi] なら AI が付けたタグとして先頭に ✨ を付ける。
 */
@Composable
private fun RemovableTagChip(name: String, isAi: Boolean, onRemove: () -> Unit) {
    Surface(
        shape = EchoMarkDesign.chipShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .padding(start = if (isAi) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isAi) {
                AiTagIcon(size = 16.dp, modifier = Modifier.padding(end = 4.dp), describe = true)
            }
            Text(
                "#$name",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 長いタグ名は省略して、× が画面の外に出ないようにする
                modifier = Modifier.widthIn(max = TAG_CHIP_LABEL_MAX_WIDTH)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "タグ「$name」を外す" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 入力に部分一致する既存タグ(付いていないもの)を、前方一致を優先して最大3件 */
internal fun tagSuggestions(input: String, allTagNames: List<String>, current: List<String>): List<String> {
    val query = input.trim().removePrefix("#").trim()
    if (query.isEmpty()) return emptyList()
    return allTagNames
        .filter { it !in current && it.contains(query, ignoreCase = true) && it != query }
        .sortedWith(compareByDescending<String> { it.startsWith(query, ignoreCase = true) }.thenBy { it.length })
        .take(3)
}

private val TAG_CHIP_LABEL_MAX_WIDTH = 220.dp
