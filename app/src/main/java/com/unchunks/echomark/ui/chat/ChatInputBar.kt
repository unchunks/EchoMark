package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.navigation.centeredMaxWidth
import com.unchunks.echomark.ui.design.EchoMarkInputChip
import com.unchunks.echomark.ui.design.designFieldStyle
import com.unchunks.echomark.ui.design.DesignBottomBar

/**
 * 次の質問に添付したファイルの名前(外せる)と、添付の読み込み中・失敗の表示。入力欄の上に出す。
 * 添付していない・読み込みも失敗もないときは何も出さない。
 */
@Composable
internal fun ChatAttachmentStatus(
    name: String?,
    isAttaching: Boolean,
    error: String?,
    onRemove: () -> Unit
) {
    if (name == null && !isAttaching && error == null) return
    Column(modifier = Modifier.centeredMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
        if (isAttaching) {
            Text(
                "ファイルを読み込んでいます…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (name != null) {
            EchoMarkInputChip(
                selected = true,
                onClick = onRemove,
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "添付を外す") }
            )
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 保存全体ではなく絞った範囲を参照しているときに、入力欄の上に出す表示。押すと範囲を選び直せる。 */
@Composable
internal fun ChatScopeStatus(label: String, onClick: () -> Unit) {
    Column(modifier = Modifier.centeredMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
        EchoMarkInputChip(
            selected = true,
            onClick = onClick,
            label = { Text("範囲: $label", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) }
        )
    }
}

/** 音声入力の間、入力欄の代わりに出す。話し終えるボタンと、やめるボタン。文字に起こしている間は待つだけ。 */
@Composable
internal fun ChatVoiceBar(state: ChatVoiceState, onFinish: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    // 入力のバー。地色・上端の区切りは、ほかの下の操作のバーと同じ
    DesignBottomBar(modifier = modifier.fillMaxWidth(), classic = MaterialTheme.colorScheme.surface, classicTonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .centeredMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "音声入力をやめる")
            }
            Text(
                text = if (state == ChatVoiceState.TRANSCRIBING) "文字に起こしています…" else "聞いています…",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            if (state == ChatVoiceState.TRANSCRIBING) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                FilledIconButton(onClick = onFinish, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "話し終える")
                }
            }
        }
    }
}

/**
 * 入力欄。複数行の丸い入力欄と、送信ボタン(空なら無効)。生成中は送信ボタンが停止ボタンに変わる。
 * 生成中も次の質問を打ち始められるよう、入力欄自体は無効にしない。
 * 何も入力していないときは、送信ボタンの代わりに音声で質問するボタンを出す。
 * 左の追加のボタンから、添付・テンプレート・参照する範囲を開く。
 * @param onScopeClick null なら参照する範囲を選べない
 */
@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceClick: () -> Unit = {},
    onAttachClick: () -> Unit = {},
    onTemplateClick: () -> Unit = {},
    onScopeClick: (() -> Unit)? = null,
    webSearchEnabled: Boolean = false,
    onWebSearchChange: ((Boolean) -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 入力のバー。地色・上端の区切りは、ほかの下の操作のバーと同じ
    DesignBottomBar(modifier = modifier.fillMaxWidth(), classic = MaterialTheme.colorScheme.surface, classicTonalElevation = 0.dp) {
        Row(
            // 広い画面では、メッセージと同じ中央の幅にそろえる
            modifier = Modifier
                .centeredMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(modifier = Modifier.padding(bottom = 4.dp)) {
                IconButton(onClick = { menuExpanded = true }, enabled = !isSending) {
                    Icon(Icons.Outlined.Add, contentDescription = "追加の操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // 添付はこの質問の根拠にだけ使う(会話には保存しない)
                    DropdownMenuItem(
                        text = { Text("ファイルを添付") },
                        leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                        onClick = { menuExpanded = false; onAttachClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("テンプレート") },
                        leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                        onClick = { menuExpanded = false; onTemplateClick() }
                    )
                    if (onScopeClick != null) {
                        DropdownMenuItem(
                            text = { Text("参照する範囲") },
                            leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                            onClick = { menuExpanded = false; onScopeClick() }
                        )
                    }
                }
            }
            if (onWebSearchChange != null) {
                Box(modifier = Modifier.padding(bottom = 4.dp)) {
                    WebSearchToggleButton(enabled = webSearchEnabled, onChange = onWebSearchChange, isSending = isSending)
                }
            }
            // 入力欄の形・地色(ソフトはくぼみ)はデザインに従う
            val field = designFieldStyle(classicContainer = MaterialTheme.colorScheme.surfaceContainerHigh)
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).then(field.depth),
                placeholder = {
                    Text(
                        if (webSearchEnabled) "ブックマークと Web をもとに質問…" else "保存したブックマークについて質問…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                shape = field.shape,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = field.containerColor,
                    unfocusedContainerColor = field.containerColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            // テキスト欄(最小 56dp)の下端にそろえ、48dp のタップ領域を確保する
            Box(modifier = Modifier.padding(bottom = 4.dp)) {
                if (isSending) {
                    FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = "生成を停止")
                    }
                } else if (value.isBlank()) {
                    FilledTonalIconButton(onClick = onVoiceClick, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Mic, contentDescription = "音声で質問")
                    }
                } else {
                    FilledIconButton(onClick = onSend, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "送信")
                    }
                }
            }
        }
    }
}
