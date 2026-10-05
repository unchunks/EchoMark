package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.ui.common.extractDomain

/**
 * AI が使えない設定のときの案内カード。会話一覧とチャット画面の上部に出す。
 * READY のときは何も表示しない。
 */
@Composable
internal fun AiSetupBanner(
    state: AiSetupState,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (state.isReady) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        if (compact) CompactAiSetupBanner(state, onOpenAiSettings) else FullAiSetupBanner(state, onOpenAiSettings)
    }
}

@Composable
private fun FullAiSetupBanner(state: AiSetupState, onOpenAiSettings: () -> Unit) {
    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("AI を設定するとチャットできます", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (state) {
                    AiSetupState.LOCAL_MODEL_MISSING ->
                        "端末内で動かす AI モデルがまだありません。モデルを取り込むか、クラウド API を選んでください。"
                    else -> "選択中のクラウド API のキーが未設定です。キーを入力するか、端末内の AI に切り替えてください。"
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenAiSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("AI 設定を開く")
            }
        }
    }
}

/** 会話中に出す1行の案内(メッセージの邪魔をしないよう小さくする)。 */
@Composable
private fun CompactAiSetupBanner(state: AiSetupState, onOpenAiSettings: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("AI が未設定です", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (state) {
                    AiSetupState.LOCAL_MODEL_MISSING -> "端末内の AI モデルが未取り込みです"
                    else -> "クラウド API のキーが未設定です"
                },
                style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph)
            )
        }
        TextButton(
            onClick = onOpenAiSettings,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
        ) { Text("設定を開く") }
    }
}

/** 会話の名前を変更するダイアログ。 */
@Composable
internal fun RenameConversationDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名前を変更") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("チャットの名前") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim()) }, enabled = title.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** 会話を削除する前の確認ダイアログ(チャット画面から削除するとき)。 */
@Composable
internal fun DeleteConversationDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("チャットを削除") },
        text = { Text("「$title」とそのメッセージを削除します。保存したブックマークは消えません。") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("削除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** サイト名 > URL のドメイン > 種類名 の順で「どこから来たか」を返す(引用カード用)。 */
internal fun Bookmark.chatSourceLabel(): String =
    siteName?.takeIf { it.isNotBlank() }
        ?: contentUri?.takeIf { type == BookmarkType.URL }?.let { extractDomain(it) }
        ?: when (type) {
            BookmarkType.URL -> "リンク"
            BookmarkType.TEXT -> "メモ"
            BookmarkType.IMAGE -> "画像"
            BookmarkType.PDF -> "PDF"
            BookmarkType.AUDIO -> "音声"
            BookmarkType.VIDEO -> "動画"
        }

/** サムネイルの色分けに使うキー(ドメイン、なければタイトル)。 */
internal fun Bookmark.chatThumbnailKey(): String =
    contentUri?.takeIf { type == BookmarkType.URL }?.let { extractDomain(it) } ?: title
