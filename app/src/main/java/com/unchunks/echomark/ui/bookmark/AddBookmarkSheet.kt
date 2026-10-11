package com.unchunks.echomark.ui.bookmark

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.view.textclassifier.TextClassifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.MAX_ATTACHMENT_BYTES
import com.unchunks.echomark.domain.bookmark.model.SUPPORTED_ATTACHMENT_MIME_TYPES
import com.unchunks.echomark.ui.common.SelectedFile
import com.unchunks.echomark.ui.common.findUrlInText
import com.unchunks.echomark.ui.common.normalizeUrlInput
import com.unchunks.echomark.ui.common.querySelectedFile
import com.unchunks.echomark.ui.components.SelectedFileRow

/** 追加シートで何を保存するか */
enum class AddMode { LINK, NOTE, FILE }

/** 追加シートの入力内容(状態を持たない描画用) */
data class AddBookmarkForm(
    val mode: AddMode = AddMode.LINK,
    val url: String = "",
    val title: String = "",
    val memo: String = "",
    val text: String = "",
    /** URL 欄に出すエラー(null なら無し) */
    val urlError: String? = null,
    /** 選んだファイル(ファイルのとき) */
    val files: List<SelectedFile> = emptyList()
) {
    val canSave: Boolean
        get() = when (mode) {
            AddMode.LINK -> url.isNotBlank()
            AddMode.NOTE -> text.isNotBlank()
            AddMode.FILE -> files.any { it.canSave }
        }

    /** 入力内容を保存用に変換する。URL の形式が正しくない・保存できるファイルが無ければ null */
    fun toInput(): NewBookmarkInput? = when (mode) {
        AddMode.LINK -> normalizeUrlInput(url)?.let { NewBookmarkInput.Link(it, title.trim(), memo.trim()) }
        AddMode.NOTE -> text.takeIf { it.isNotBlank() }?.let { NewBookmarkInput.Note(it, title.trim()) }
        AddMode.FILE -> files.filter { it.canSave }.takeIf { it.isNotEmpty() }?.let {
            // タイトルは1件のときだけ使う(複数ならそれぞれのファイル名)
            NewBookmarkInput.Files(it, title.trim().takeIf { _ -> files.size == 1 }.orEmpty())
        }
    }
}

/**
 * ブックマークの追加シート。リンク/メモ/ファイルを切り替えて入力し、[onSave] で保存する。
 * ファイルは端末のファイル選択(複数可)で選ぶ。
 * 開いたときにクリップボードへ URL がありそうなら「クリップボードの URL を貼り付け」を提案する
 * (Android 12 以降は、中身を読むとシステムの通知が出るため、提案の判定には分類結果だけを使い、読むのは押したときだけにする)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookmarkSheet(
    onDismiss: () -> Unit,
    onSave: (NewBookmarkInput) -> Unit
) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(AddMode.LINK) }
    var url by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var files by rememberSaveable(stateSaver = SelectedFile.ListSaver) { mutableStateOf(emptyList<SelectedFile>()) }
    val clipboardHasUrl = remember { clipboardLikelyHasUrl(context) }
    val form = AddBookmarkForm(mode, url, title, memo, text, urlError, files)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val picked = uris.map { querySelectedFile(context.contentResolver, it) }
        files = (files + picked).distinctBy { it.uri }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        AddBookmarkSheetContent(
            form = form,
            showClipboardSuggestion = clipboardHasUrl && url.isBlank(),
            autoFocus = true,
            onModeChange = { mode = it },
            onUrlChange = {
                url = it
                urlError = null
            },
            onTitleChange = { title = it },
            onMemoChange = { memo = it },
            onTextChange = { text = it },
            onPickFiles = { pickFiles.launch(SUPPORTED_ATTACHMENT_MIME_TYPES.toTypedArray()) },
            onRemoveFile = { removed -> files = files.filterNot { it.uri == removed.uri } },
            onPasteFromClipboard = {
                val pasted = readClipboardUrl(context)
                if (pasted != null) {
                    url = pasted
                    urlError = null
                } else {
                    urlError = "クリップボードに URL がありません"
                }
            },
            onCancel = onDismiss,
            onSave = {
                val input = form.toInput()
                if (input == null) {
                    urlError = "URL の形式が正しくありません(例: https://example.com)"
                } else {
                    onSave(input)
                }
            }
        )
    }
}

/** 追加シートの中身。状態を受け取って描くだけなので、スクリーンショットテストで単体描画できる */
@Composable
fun AddBookmarkSheetContent(
    form: AddBookmarkForm,
    showClipboardSuggestion: Boolean,
    onModeChange: (AddMode) -> Unit,
    onUrlChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMemoChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onPickFiles: () -> Unit = {},
    onRemoveFile: (SelectedFile) -> Unit = {}
) {
    val firstFieldFocus = remember { FocusRequester() }
    if (autoFocus) {
        // ファイルは入力欄ではなく選ぶボタンから始めるので、キーボードを出さない
        LaunchedEffect(form.mode) { if (form.mode != AddMode.FILE) runCatching { firstFieldFocus.requestFocus() } }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ブックマークを追加",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AddMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = form.mode == item,
                    onClick = { onModeChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, AddMode.entries.size),
                    icon = {
                        SegmentedButtonDefaults.Icon(active = form.mode == item) {
                            Icon(
                                imageVector = when (item) {
                                    AddMode.LINK -> Icons.Outlined.Link
                                    AddMode.NOTE -> Icons.AutoMirrored.Outlined.Notes
                                    AddMode.FILE -> Icons.Outlined.AttachFile
                                },
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                            )
                        }
                    }
                ) {
                    Text(
                        when (item) {
                            AddMode.LINK -> "リンク"
                            AddMode.NOTE -> "メモ"
                            AddMode.FILE -> "ファイル"
                        },
                        maxLines = 1
                    )
                }
            }
        }

        when (form.mode) {
            AddMode.LINK -> {
                if (showClipboardSuggestion) {
                    AssistChip(
                        onClick = onPasteFromClipboard,
                        label = { Text("クリップボードの URL を貼り付け") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        }
                    )
                }
                OutlinedTextField(
                    value = form.url,
                    onValueChange = onUrlChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(firstFieldFocus),
                    label = { Text("URL") },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = form.urlError != null,
                    supportingText = form.urlError?.let { { Text(it) } },
                    trailingIcon = {
                        IconButton(onClick = onPasteFromClipboard) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = "クリップボードから貼り付け")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("タイトル(任意)") },
                    supportingText = { Text("空欄なら、ページのタイトルを自動で取得します") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = form.memo,
                    onValueChange = onMemoChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("メモ(任意)") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
            AddMode.NOTE -> {
                OutlinedTextField(
                    value = form.text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(firstFieldFocus),
                    label = { Text("本文") },
                    placeholder = { Text("残しておきたいことを書く") },
                    minLines = 4
                )
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("タイトル(任意)") },
                    supportingText = { Text("空欄なら、本文の1行目をタイトルにします") },
                    singleLine = true
                )
            }
            AddMode.FILE -> FileInputs(form, onPickFiles, onRemoveFile, onTitleChange)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (form.mode == AddMode.FILE) "保存すると中身を読み取って、AI が要約します" else "保存すると AI が要約とタグ付けをします",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("キャンセル") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSave, enabled = form.canSave) {
                val count = form.files.count { it.canSave }
                Text(if (form.mode == AddMode.FILE && count > 1) "${count}件を保存" else "保存")
            }
        }
    }
}

/** ファイルの入力: 選ぶボタン・選んだファイルの一覧(外せる)・タイトル(1件のとき) */
@Composable
private fun FileInputs(
    form: AddBookmarkForm,
    onPickFiles: () -> Unit,
    onRemoveFile: (SelectedFile) -> Unit,
    onTitleChange: (String) -> Unit
) {
    FilledTonalButton(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(if (form.files.isEmpty()) "ファイルを選ぶ" else "ほかのファイルも選ぶ")
    }
    Text(
        text = "画像・PDF・音声・動画・テキストを保存できます(1件 ${MAX_ATTACHMENT_BYTES / (1024 * 1024)}MB まで。複数選べます)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    form.files.forEach { file ->
        SelectedFileRow(file = file, onRemove = { onRemoveFile(file) })
    }
    if (form.files.size == 1) {
        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("タイトル(任意)") },
            supportingText = { Text("空欄なら、ファイル名をタイトルにします") },
            singleLine = true
        )
    }
}

/**
 * クリップボードに URL がありそうか。中身は読まず、システムの分類結果(Android 12+)で判定する。
 * 分類がまだ・使えないときは提案しない(URL 欄の貼り付けボタンはいつでも使える)。
 */
private fun clipboardLikelyHasUrl(context: Context): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    val description = clipboard.primaryClipDescription ?: return false
    val isText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    if (!isText) return false
    return if (description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE) {
        description.getConfidenceScore(TextClassifier.TYPE_URL) >= URL_CONFIDENCE
    } else {
        false
    }
}

/** クリップボードの文字列から URL を取り出す(利用者が貼り付けを押したときだけ呼ぶ)。 */
private fun readClipboardUrl(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    return findUrlInText(text) ?: normalizeUrlInput(text)
}

private const val URL_CONFIDENCE = 0.5f
