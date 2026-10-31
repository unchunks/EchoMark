package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unchunks.components.embedding.EmbeddingModelProfile
import com.unchunks.components.filestorage.formatBytes
import com.unchunks.echomark.data.ai.model.EmbeddingModelInfo
import com.unchunks.echomark.data.ai.model.InstalledModels
import com.unchunks.echomark.data.ai.model.LocalModelInfo
import com.unchunks.echomark.data.ai.model.LocalModelLoadFailure
import com.unchunks.echomark.data.ai.model.ModelBenchmarkResult
import com.unchunks.echomark.data.ai.model.ModelBenchmarkState
import com.unchunks.echomark.data.ai.model.ModelImportState
import com.unchunks.echomark.domain.model.EmbeddingProgress
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.settings.SettingsItem
import com.unchunks.echomark.ui.settings.SettingsNotice
import com.unchunks.echomark.ui.settings.SettingsSwitchItem
import com.unchunks.echomark.ui.settings.japaneseParagraph
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * AI 設定画面の「端末内モデル」と「検索用の埋め込みモデル」。モデルファイルの取り込み・入れ替え・削除。
 */

/**
 * 取り込んだ端末内モデルの一覧(既定のモデル・削除)と、モデルの取り込み。
 * 2つ以上取り込むと、用途ごとに使うモデルを選べる(「用途ごとの AI」)。用途で選んでいなければ既定のモデルを使う。
 */
@Composable
internal fun LocalModelSection(
    models: InstalledModels,
    loadFailures: Map<String, LocalModelLoadFailure>,
    importState: ModelImportState,
    isSelected: Boolean,
    onPickModel: () -> Unit,
    onCancelImport: () -> Unit,
    onDelete: (LocalModelInfo) -> Unit,
    onSetDefault: (LocalModelInfo) -> Unit,
    benchmarks: List<ModelBenchmarkResult> = emptyList(),
    benchmarkState: ModelBenchmarkState = ModelBenchmarkState.Idle,
    onBenchmark: (LocalModelInfo) -> Unit = {}
) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<LocalModelInfo?>(null) }

    if (models.models.isEmpty()) {
        SettingsItem(
            title = "未取り込み",
            icon = Icons.Outlined.Memory,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            summary = if (isSelected) "端末内で要約・チャットを行うには、モデルファイルを取り込んでください" else "取り込むと、端末内でも AI を使えます"
        )
    }
    models.models.forEach { model ->
        val isDefault = model == models.default
        SettingsItem(
            title = model.displayName,
            icon = Icons.Outlined.Memory,
            iconTint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            summary = buildString {
                if (isDefault && models.models.size >= 2) append("既定・")
                append(formatBytes(model.sizeBytes))
                append("・取り込み: ")
                append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(model.importedAt)))
            },
            trailing = {
                IconButton(onClick = { deleting = model }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "「${model.displayName}」を削除")
                }
            }
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val failure = loadFailures[model.id]
            if (failure != null) {
                StatusLine(
                    icon = {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    text = failure.userMessage,
                    isError = true
                )
            } else if (model.isLegacyTaskFormat) {
                // 以前の版(MediaPipe の LLM Inference)で取り込んだ .task も、そのまま LiteRT-LM で動かす
                SettingsNotice(
                    text = "旧形式(.task)のモデルです。そのまま使えますが、読み込めない・回答が崩れるときは、" +
                        "同じモデルの .litertlm 版を取り込み直してください。",
                    icon = Icons.Outlined.Info
                )
            }
            ModelBenchmarkLines(model, benchmarks.filter { it.modelId == model.id }, benchmarkState, onBenchmark)
            if (!isDefault) {
                OutlinedButton(onClick = { onSetDefault(model) }) { Text("既定のモデルにする") }
            }
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (importState) {
            is ModelImportState.Copying -> {
                val fraction = importState.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "取り込み中… ${(fraction * 100).toInt()}%(${formatBytes(importState.copiedBytes)} / ${formatBytes(importState.totalBytes)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("取り込み中… ${formatBytes(importState.copiedBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onCancelImport) { Text("取り込みを中止") }
            }
            else -> {
                if (importState is ModelImportState.Failed) {
                    StatusLine(
                        icon = {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        text = "取り込めませんでした: ${importState.error.userMessage}",
                        isError = true
                    )
                }
                Button(onClick = onPickModel) {
                    ButtonIcon(Icons.Outlined.UploadFile)
                    Text(if (models.models.isEmpty()) "モデルファイルを取り込む" else "別のモデルを追加する")
                }
            }
        }
        Text(
            "入手方法: Hugging Face の litert-community で公開されている Gemma 3 1B IT・Qwen3 0.6B" +
                "(例: Gemma3-1B-IT の int4 版 .litertlm)などを端末にダウンロードし、上のボタンで選んでください。" +
                "Gemma のダウンロードには、Hugging Face へのログインと利用規約への同意が必要です。\n" +
                "対応形式: .litertlm(推奨)/ .task(旧形式)\n" +
                "複数のモデルを取り込むと、用途(タグ付け・要約・チャット)ごとに使うモデルを選べます。" +
                "モデルは1つあたり数百MB〜数GBあるため、使わないモデルは削除してください。",
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { openUrl(context, MODEL_GUIDE_URL) },
            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
        ) {
            ButtonIcon(Icons.AutoMirrored.Outlined.OpenInNew)
            Text("Hugging Face(litert-community)を開く")
        }
    }

    deleting?.let { model ->
        DeleteLocalModelDialog(
            modelName = model.displayName,
            isLast = models.models.size <= 1,
            onConfirm = {
                deleting = null
                onDelete(model)
            },
            onDismiss = { deleting = null }
        )
    }
}

/** [model] をこの端末で測った結果([results]。演算装置ごと)と、測るボタン。ほかのモデルを測っている間は押せない。 */
@Composable
private fun ModelBenchmarkLines(
    model: LocalModelInfo,
    results: List<ModelBenchmarkResult>,
    state: ModelBenchmarkState,
    onBenchmark: (LocalModelInfo) -> Unit
) {
    results.sortedBy { it.backend }.forEach { result ->
        Text(
            benchmarkSummary(result),
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (state is ModelBenchmarkState.Running && state.model == model) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            "測定中…(1 分前後かかります。終わるまで端末内 AI の処理は待たされます)",
            style = MaterialTheme.typography.bodySmall.japaneseParagraph()
        )
        return
    }
    if (state is ModelBenchmarkState.Failed && state.model == model) {
        StatusLine(
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = state.message,
            isError = true
        )
    }
    OutlinedButton(onClick = { onBenchmark(model) }, enabled = state !is ModelBenchmarkState.Running) {
        ButtonIcon(Icons.Outlined.Speed)
        Text(if (results.isEmpty()) "この端末での速さを測る" else "もう一度測る")
    }
}

/** 測定の結果の1行。 */
internal fun benchmarkSummary(result: ModelBenchmarkResult): String = buildString {
    append(result.backend.name)
    append(": 読み込み ${oneDecimal(result.loadSeconds)} 秒")
    append("・入力 ${oneDecimal(result.prefillTokensPerSecond)} トークン/秒")
    append("・出力 ${oneDecimal(result.decodeTokensPerSecond)} トークン/秒")
    result.peakMemoryBytes?.let { append("・メモリ +${formatBytes(it)}") }
    append("(")
    append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(result.measuredAt)))
    append(" 測定)")
}

private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * 端末内 AI を GPU で動かすかのスイッチ(実験的)。速くなるが、端末・モデルによってはアプリが終了することがあるため既定はオフ。
 * GPU で落ちたモデルは次から自動で CPU に戻ることも伝える。
 */
@Composable
internal fun LocalLlmGpuSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsSwitchItem(
        title = "GPU で動かす(実験的)",
        icon = Icons.Outlined.Memory,
        checked = checked,
        onCheckedChange = onCheckedChange,
        summary = "対応する端末とモデルでは要約・タグ付けが速くなります",
        modifier = Modifier.padding(top = 8.dp)
    )
    if (checked) {
        SettingsNotice(
            text = "端末やモデルによっては、GPU で動かすとアプリが終了することがあります。" +
                "そのときは次から自動で CPU に戻します。",
            icon = Icons.Outlined.Info
        )
    }
}

/**
 * 意味での検索・関連ブックマーク・チャットに使う埋め込みモデル。
 * 何も取り込んでいなければアプリ同梱のモデル。取り込むと、そのモデルで検索インデックスを作り直す。
 */
@Composable
internal fun EmbeddingModelSection(
    model: EmbeddingModelInfo?,
    importState: ModelImportState,
    onPickModel: (EmbeddingModelProfile) -> Unit,
    onCancelImport: () -> Unit,
    onRevert: () -> Unit
) {
    var showProfileDialog by remember { mutableStateOf(false) }

    SettingsItem(
        title = model?.file?.displayName ?: "${EmbeddingModels.BUNDLED.displayName}(同梱)",
        icon = Icons.Outlined.Memory,
        iconTint = if (model != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        summary = if (model == null) {
            "アプリに同梱のモデルを使っています"
        } else {
            "種類: ${model.profile.displayName}・${formatBytes(model.file.sizeBytes)}・取り込み: " +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(model.file.importedAt))
        }
    )

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (importState) {
            is ModelImportState.Copying -> {
                val fraction = importState.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "取り込み中… ${(fraction * 100).toInt()}%(${formatBytes(importState.copiedBytes)} / ${formatBytes(importState.totalBytes)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("取り込み中… ${formatBytes(importState.copiedBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onCancelImport) { Text("取り込みを中止") }
            }
            else -> {
                if (importState is ModelImportState.Failed) {
                    StatusLine(
                        icon = {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        text = "取り込めませんでした: ${importState.error.userMessage}",
                        isError = true
                    )
                }
                Button(onClick = { showProfileDialog = true }) {
                    ButtonIcon(Icons.Outlined.UploadFile)
                    Text(if (model == null) "埋め込みモデルを取り込む" else "別のモデルに入れ替える")
                }
                if (model != null) {
                    OutlinedButton(onClick = onRevert) {
                        ButtonIcon(Icons.Outlined.Refresh)
                        Text("同梱のモデルに戻す")
                    }
                }
            }
        }
        Text(
            "モデルを変えると、保存済みのブックマークの検索インデックスを新しいモデルで作り直します" +
                "(その間、まだ作り直していないブックマークは意味での検索・関連ブックマーク・チャットに出ません)。" +
                "対応形式: .task / .tflite",
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (model?.profile?.experimental == true) {
            SettingsNotice(
                text = "実験的なモデルです。端末の MediaPipe が読み込めないと、検索インデックスを作れず意味での検索が使えなくなります。" +
                    "その場合は「同梱のモデルに戻す」を選んでください。",
                icon = Icons.Outlined.WarningAmber,
                isWarning = true
            )
        }
    }

    if (showProfileDialog) {
        EmbeddingProfileDialog(
            onSelect = {
                showProfileDialog = false
                onPickModel(it)
            },
            onDismiss = { showProfileDialog = false }
        )
    }
}

/** 埋め込み(検索インデックス)を作り直している間の進み具合。 */
@Composable
internal fun EmbeddingIndexProgress(progress: EmbeddingProgress) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "検索インデックスを更新中 (${progress.embedded}/${progress.total})",
            style = MaterialTheme.typography.bodyMedium
        )
        LinearProgressIndicator(
            progress = { if (progress.total > 0) progress.embedded.toFloat() / progress.total else 0f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "意味での検索・関連ブックマーク・チャットは、更新が済んだ分から使えます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val MODEL_GUIDE_URL = "https://huggingface.co/litert-community"
