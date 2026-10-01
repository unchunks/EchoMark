package com.unchunks.echomark.ui.share

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.MainUiState
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.isDark
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/** 他アプリの共有メニュー(ACTION_SEND, text/plain)から受け取ってクイック保存するActivity */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    /** テーマ設定の読み込み(アプリ本体と同じ) */
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val shared = parseSharedContent(intent)
        if (shared == null) {
            Toast.makeText(this, "保存できる内容がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            // アプリのテーマ設定(ライト/ダーク・ダイナミックカラー)に合わせる。読み込み前(ほんの一瞬)は何も描かない
            val appState by appViewModel.uiState.collectAsState()
            val ready = appState as? MainUiState.Ready ?: return@setContent
            val darkTheme = ready.themeMode.isDark(isSystemInDarkTheme())
            // ナビゲーションバーのアイコン色も、端末ではなくアプリのテーマに合わせる
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
                )
                onDispose {}
            }
            EchoMarkTheme(darkTheme = darkTheme, dynamicColor = ready.dynamicColor) {
                val status by viewModel.status.collectAsState()
                // 保存できたら、シート内で結果を短く見せてから閉じる
                LaunchedEffect(status) {
                    if (status is ShareSaveStatus.Saved) {
                        delay(SAVED_DISPLAY_MILLIS)
                        finish()
                    }
                }
                QuickSaveSheet(
                    shared = shared,
                    status = status,
                    onSave = { title, memo, tags -> viewModel.save(shared, title, memo, tags) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private companion object {
        const val SAVED_DISPLAY_MILLIS = 1_200L
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
    status: ShareSaveStatus,
    onSave: (title: String, memo: String, tags: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 仮タイトルが URL そのものなら空欄にして、ページのタイトルを自動で使う
    var title by rememberSaveable { mutableStateOf(shared.tentativeTitle.takeIf { it != shared.url }.orEmpty()) }
    var memo by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        ShareSheetContent(
            shared = shared,
            status = status,
            title = title,
            memo = memo,
            tags = tags,
            onTitleChange = { title = it },
            onMemoChange = { memo = it },
            onTagsChange = { tags = it },
            onSave = { onSave(title, memo, tags) },
            onCancel = onDismiss
        )
    }
}

/** 共有シートの中身(状態を受け取って描くだけ)。保存後は結果の表示に切り替える */
@Composable
fun ShareSheetContent(
    shared: SharedContent,
    status: ShareSaveStatus,
    title: String,
    memo: String,
    tags: String,
    onTitleChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedContent(targetState = status is ShareSaveStatus.Saved, label = "share_sheet", modifier = modifier) { saved ->
        if (saved) {
            SavedResult(isDuplicate = (status as? ShareSaveStatus.Saved)?.isDuplicate == true, isLink = shared.url != null)
        } else {
            ShareForm(
                shared = shared,
                status = status,
                title = title,
                memo = memo,
                tags = tags,
                onTitleChange = onTitleChange,
                onMemoChange = onMemoChange,
                onTagsChange = onTagsChange,
                onSave = onSave,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun ShareForm(
    shared: SharedContent,
    status: ShareSaveStatus,
    title: String,
    memo: String,
    tags: String,
    onTitleChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val saving = status == ShareSaveStatus.Saving
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Bookmarks,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "EchoMarkに保存",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "保存すると AI が要約とタグ付けをします",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SharedPreview(shared)

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (shared.url != null) "タイトル(任意)" else "タイトル") },
            supportingText = if (shared.url != null) {
                { Text("空欄なら、ページのタイトルを自動で取得します") }
            } else {
                null
            },
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = memo,
            onValueChange = onMemoChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("メモ(任意)") },
            enabled = !saving,
            minLines = 2
        )
        OutlinedTextField(
            value = tags,
            onValueChange = onTagsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("タグ(任意)") },
            placeholder = { Text("例: Android, あとで読む") },
            supportingText = { Text("カンマや空白で区切ると複数付けられます") },
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        (status as? ShareSaveStatus.Failed)?.let { failed ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(failed.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel, enabled = !saving) { Text("キャンセル") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !saving && (shared.url != null || title.isNotBlank() || shared.text.isNotBlank()),
                onClick = onSave
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("保存中…")
                } else {
                    Text("保存")
                }
            }
        }
    }
}

/** 共有された URL(ドメインと URL)またはテキストの冒頭を見せる */
@Composable
private fun SharedPreview(shared: SharedContent) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (shared.url != null) Icons.Outlined.Link else Icons.AutoMirrored.Outlined.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (shared.url != null) {
                    Text(
                        extractDomain(shared.url),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shared.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        shared.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 保存後に短く見せる結果 */
@Composable
private fun SavedResult(isDuplicate: Boolean, isLink: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (isDuplicate) "既に保存済みです" else "保存しました",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    isDuplicate -> "同じ URL のブックマークがあります"
                    isLink -> "ページを読み込んで、AI が要約します"
                    else -> "AI が要約とタグ付けをします"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
