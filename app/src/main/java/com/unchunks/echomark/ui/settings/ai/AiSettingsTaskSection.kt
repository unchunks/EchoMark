package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.provider.presets
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.settings.SettingsNotice
import com.unchunks.echomark.ui.settings.SettingsSwitchItem
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.settings.shortName

/*
 * AI 設定画面の「用途ごとの AI」。用途ごとの実行場所・提供元・モデルの選択と、クラウドに送る内容の説明。
 */

private val AiTask.icon: ImageVector
    get() = when (this) {
        AiTask.TAGGING -> Icons.AutoMirrored.Outlined.Label
        AiTask.SUMMARY -> Icons.AutoMirrored.Outlined.Notes
        AiTask.CHAT -> Icons.Outlined.Forum
    }

private val AiTask.description: String
    get() = when (this) {
        AiTask.TAGGING -> "保存したブックマークにタグとカテゴリを付けます"
        AiTask.SUMMARY -> "保存したブックマークの要約を作ります(音声・動画をクラウドで文字起こしするかも、この設定に従います)"
        AiTask.CHAT -> "保存した内容をもとに、チャットの質問に答えます"
    }

/** 1つの用途の設定(実行場所と、クラウド API なら提供元・モデル)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskSettingCard(
    task: AiTask,
    uiState: AiSettingsUiState,
    onSelectBackend: (LlmBackend) -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    onSetModel: (ApiProvider, String) -> Unit
) {
    val setting = uiState.setting(task)
    val provider = setting.apiProvider
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(task.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(task.label, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                task.description,
                style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LlmBackend.entries.forEachIndexed { index, backend ->
                    SegmentedButton(
                        selected = setting.backend == backend,
                        onClick = { onSelectBackend(backend) },
                        shape = SegmentedButtonDefaults.itemShape(index, LlmBackend.entries.size)
                    ) {
                        Text(
                            when (backend) {
                                LlmBackend.LOCAL -> "端末内"
                                LlmBackend.API -> "クラウド API"
                            }
                        )
                    }
                }
            }
            if (setting.backend == LlmBackend.API) {
                FieldLabel("提供元")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApiProvider.entries.forEach { option ->
                        FilterChip(
                            selected = option == provider,
                            onClick = { onSelectProvider(option) },
                            label = { Text(option.shortName) }
                        )
                    }
                }
                FieldLabel("モデル")
                ModelSelector(
                    provider = provider,
                    selectedModel = setting.apiModel,
                    onSetModel = { onSetModel(provider, it) }
                )
            }
            if (!uiState.isReady(task)) {
                StatusLine(
                    icon = {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    text = when (setting.backend) {
                        LlmBackend.LOCAL -> "端末内モデルが未取り込みです(下の「端末内モデル」で取り込めます)"
                        LlmBackend.API -> "${provider.shortName} の API キーが未設定です(下の「クラウド API のキー」で保存できます)"
                    },
                    isError = true
                )
            }
        }
    }
}

/** 提供元のモデルをプリセットから選ぶか、ID を自由入力する。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSelector(provider: ApiProvider, selectedModel: String, onSetModel: (String) -> Unit) {
    var modelInput by remember(provider, selectedModel) { mutableStateOf(selectedModel) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        provider.presets.forEach { preset ->
            FilterChip(
                selected = selectedModel == preset.id,
                onClick = { onSetModel(preset.id) },
                label = { Text(preset.label) }
            )
        }
    }
    OutlinedTextField(
        value = modelInput,
        onValueChange = { modelInput = it },
        label = { Text("モデル ID(自由入力)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        trailingIcon = {
            if (modelInput.trim() != selectedModel) {
                TextButton(onClick = { onSetModel(modelInput) }) { Text("適用") }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** クラウド API を使う用途で、何がどの提供元に送られるか。 */
internal fun cloudNotice(uiState: AiSettingsUiState): String {
    val destinations = uiState.apiTasks.joinToString("・") { "${it.label}: ${uiState.setting(it).apiProvider.shortName}" }
    val analysisUsesApi = uiState.apiTasks.any { it != AiTask.CHAT }
    val contents = buildList {
        if (analysisUsesApi) add("ブックマークの本文")
        if (analysisUsesApi && uiState.sendFilesToCloud) add("保存したファイル")
        if (AiTask.CHAT in uiState.apiTasks) add("要約・チャットの質問")
    }.joinToString("・")
    return "クラウド API を選んだ用途($destinations)では、${contents}が提供元のサーバーに送信されます。" +
        "送信内容の扱いは各社の規約に従い、API の利用料金は各社のアカウントに請求されます。"
}

/**
 * ファイルそのものもクラウドに送るかのスイッチ。送ると写っているもの・話している内容から要約できるが、
 * 送信される情報と API の料金が増えるため、送れる種類と料金を添えて伝える。
 * @param providers タグ付け・要約で使う提供元
 */
@Composable
internal fun SendFilesToCloudSetting(providers: List<ApiProvider>, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SettingsSwitchItem(
        title = "ファイルもクラウドで解析",
        icon = Icons.Outlined.CloudUpload,
        checked = checked,
        onCheckedChange = onCheckedChange,
        summary = "保存した画像・PDF・音声・動画を ${providers.joinToString("・") { it.shortName }} に送り、" +
            "写っているものや話している内容から要約・タグ付けします",
        modifier = Modifier.padding(top = 8.dp)
    )
    SettingsNotice(text = sendFilesNotice(providers, checked), icon = Icons.Outlined.Info)
}

/** ファイルの送信について、提供元ごとに送れる種類・料金・オフのときの動きを説明する。 */
internal fun sendFilesNotice(providers: List<ApiProvider>, enabled: Boolean): String {
    val cost = "ファイルを送ると、その分の API の料金がかかります(ページの多い PDF・長い音声や動画ほど高くなります)。"
    if (!enabled) {
        return "オフのときは、端末内でファイルから読み取った文字だけを送ります。文字の無い写真などは要約できません。"
    }
    val kinds = providers.distinct().joinToString("") { provider ->
        when (provider) {
            ApiProvider.GEMINI ->
                "${provider.shortName} には画像・PDF・音声・動画を送れます(1ファイル 10 MB まで。画像は縮小して送ります)。"
            ApiProvider.CLAUDE, ApiProvider.OPENAI ->
                "${provider.shortName} に送れるのは画像と PDF です(画像は縮小して送ります)。音声・動画は端末内で文字起こしした文字だけを送ります。"
        }
    }
    return kinds + cost
}
