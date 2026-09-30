package com.unchunks.echomark.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.viewModels
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.ui.bookmark.BookmarkViewModel
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint

/** 他アプリの共有メニュー(ACTION_SEND, text/plain)から受け取ってクイック保存するActivity */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: BookmarkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = parseSharedContent(intent)
        if (shared == null) {
            Toast.makeText(this, "保存できる内容がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            EchoMarkTheme {
                QuickSaveSheet(
                    shared = shared,
                    onSave = { title, memo -> save(shared, title, memo) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun save(shared: SharedContent, title: String, memo: String) {
        val onSaved: (SaveResult) -> Unit = { result ->
            val message = if (result.isDuplicate) "既に保存済みです" else "保存しました"
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
        if (shared.url != null) {
            viewModel.saveUrlBookmark(shared.url, title, memo, onSaved)
        } else {
            // URLがなければテキストとして保存(本文=共有テキスト、メモがあれば追記)
            val content = if (memo.isBlank()) shared.text else shared.text + "\n\n" + memo.trim()
            viewModel.saveTextBookmark(title, content, onSaved)
        }
    }
}

/** 共有インテントから取り出した内容。url が null ならテキストとして扱う */
data class SharedContent(
    val url: String?,
    val text: String,
    val tentativeTitle: String
)

private val URL_REGEX = Regex("""https?://[^\s<>"']+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}」』）、。"

internal fun parseSharedContent(intent: Intent): SharedContent? {
    if (intent.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
    if (text.isEmpty()) return null
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }

    val rawUrl = URL_REGEX.find(text)?.value
    val url = rawUrl?.trimEnd { it in TRAILING_PUNCTUATION }?.takeIf { it.length > "https://".length }

    if (url != null) {
        // 「タイトル + URL」形式で共有するアプリ向けに、URL以外の部分を仮タイトルの候補にする
        val remaining = text.replace(rawUrl, "").trim().lines().firstOrNull { it.isNotBlank() }?.trim()
        return SharedContent(url = url, text = text, tentativeTitle = subject ?: remaining ?: url)
    }
    val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return SharedContent(url = null, text = text, tentativeTitle = subject ?: firstLine.take(80))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSaveSheet(
    shared: SharedContent,
    onSave: (title: String, memo: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(shared.tentativeTitle) }
    var memo by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("EchoMarkに保存", style = MaterialTheme.typography.titleMedium)
            Text(
                shared.url ?: shared.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("タイトル") }
            )
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("メモ(任意)") }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                Button(
                    enabled = !saving && (shared.url != null || title.isNotBlank()),
                    onClick = {
                        saving = true
                        onSave(title, memo)
                    }
                ) { Text("保存") }
            }
        }
    }
}
