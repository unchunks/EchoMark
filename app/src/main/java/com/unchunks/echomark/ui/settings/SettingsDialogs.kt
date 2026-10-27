package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.BackupImportSummary
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ThemeMode
import java.time.DayOfWeek

/*
 * 設定画面([SettingsContent])から開くダイアログ。
 */

/** ラジオボタンで1つ選ぶダイアログ。行全体をタップ対象にする。 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun ThemeModeDialog(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = "テーマ",
        options = ThemeMode.entries,
        selected = selected,
        label = { it.label },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
internal fun DayOfWeekDialog(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit, onDismiss: () -> Unit) {
    // 日曜始まりで並べる
    val days = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }
    ChoiceDialog(
        title = "通知する曜日",
        options = days,
        selected = selected,
        label = { it.fullLabel },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(hour: Int, minute: Int, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知する時刻") },
        text = { TimePicker(state = timeState) },
        confirmButton = { TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun DeleteConfirmDialog(
    resetSettings: Boolean,
    onResetSettingsChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
        title = { Text("すべてのデータを削除しますか?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "保存したブックマーク・タグ・会話・ファイルと、検索用のデータを削除します。必要なら先にバックアップを書き出してください。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = resetSettings, role = Role.Checkbox, onValueChange = onResetSettingsChange),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = resetSettings, onCheckedChange = null)
                    Spacer(Modifier.size(12.dp))
                    Text("設定と API キーも初期化する", style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                }
                Text(
                    "端末内モデルは残ります(AI の設定から削除できます)。",
                    style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onNext) { Text("次へ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun DeleteFinalDialog(resetSettings: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("本当に削除しますか?") },
        text = {
            Text(
                if (resetSettings) "データの削除と設定の初期化は取り消せません。" else "削除したデータは元に戻せません。",
                style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("削除する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
internal fun PrivacyDialog(backends: Set<LlmBackend>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
        title = { Text("プライバシー") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrivacyPoint(
                    "保存場所",
                    "ブックマーク・タグ・会話は、この端末の中だけに保存します。EchoMark のサーバーはありません。"
                )
                PrivacyPoint(
                    "端末内で動かす(既定)",
                    "要約・タグ付け・チャット・検索は端末内で処理し、内容を外部に送信しません。"
                )
                PrivacyPoint(
                    "クラウド API を選んだとき",
                    "クラウド API を選んだ用途(タグ付け・要約・チャット)では、その処理に使うブックマークの本文・要約やチャットの質問を、" +
                        "選んだ提供元(Anthropic・Google・OpenAI)のサーバーに送信します。" +
                        "検索用のデータは引き続き端末内で作ります。"
                )
                PrivacyPoint(
                    "URL の保存",
                    "本文とプレビュー画像を取得するため、保存したページにアクセスします。"
                )
                PrivacyPoint(
                    "API キー",
                    "端末内で暗号化して保存し、バックアップには含めません。"
                )
                Text(
                    "いまの設定: " + when {
                        LlmBackend.API !in backends -> "端末内で動かす"
                        LlmBackend.LOCAL !in backends -> "クラウド API を使う"
                        else -> "用途ごとに端末内とクラウド API を使い分ける"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

@Composable
private fun PrivacyPoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium.japaneseParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * アプリに同梱されるオープンソースのライブラリとライセンス(プロジェクトごとにまとめたもの)。
 * 依存との対応と出典は docs/third-party-licenses.md。依存を変えたら両方を直す。
 */
private val OPEN_SOURCE_LIBRARIES = listOf(
    "Android Jetpack(Compose・Room・WorkManager・DataStore・Navigation・Glance・ExifInterface ほか)" to "Apache License 2.0",
    "Kotlin・kotlinx.coroutines・kotlinx.serialization" to "Apache License 2.0",
    "Dagger / Hilt" to "Apache License 2.0",
    "Material Icons" to "Apache License 2.0",
    "MediaPipe Tasks(テキスト埋め込み)" to "Apache License 2.0",
    "LiteRT-LM(端末内 AI のモデルの実行)" to "Apache License 2.0",
    "Gson" to "Apache License 2.0",
    "ObjectBox Java" to "Apache License 2.0",
    "OkHttp・Okio" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Timber" to "Apache License 2.0",
    "PdfBox-Android" to "Apache License 2.0",
    "Guava・Flogger" to "Apache License 2.0",
    "Jackson・ClassMate・JSON Schema Generator・Swagger Annotations" to "Apache License 2.0",
    "Firebase Components・Firebase Encoders・Google Data Transport" to "Apache License 2.0",
    "Accompanist・greenrobot Essentials" to "Apache License 2.0",
    "javax.inject・Jakarta Inject・JSR 305・JSpecify・Error Prone・J2ObjC・JetBrains のアノテーション" to "Apache License 2.0",
    "jsoup" to "MIT License",
    "Anthropic Java SDK" to "MIT License",
    "Standard Webhooks・SLF4J" to "MIT License",
    "Animal Sniffer のアノテーション" to "MIT License",
    "Checker Framework のアノテーション" to "MIT License / GPL 2.0 with Classpath Exception",
    "Protocol Buffers" to "BSD 3-Clause License",
    "Bouncy Castle" to "Bouncy Castle Licence",
    "LMDB(ObjectBox のネイティブライブラリに含まれる)" to "OpenLDAP Public License 2.8"
)

/** アプリに同梱される、オープンソースでないライブラリ・モデルと利用規約。 */
private val NON_OPEN_SOURCE_COMPONENTS = listOf(
    "ML Kit(文字認識・画像ラベル)" to "ML Kit Terms of Service",
    "Google Play 開発者サービス・ODML Image" to "Android Software Development Kit License",
    "ObjectBox(ネイティブライブラリ)" to "ObjectBox Binary Licence",
    "EmbeddingGemma(埋め込みモデル)" to "Gemma Terms of Use"
)

@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("オープンソースライセンス") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "EchoMark は次のオープンソースソフトウェアなどを利用しています。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                HorizontalDivider()
                OPEN_SOURCE_LIBRARIES.forEach { (name, license) -> LicenseEntry(name, license) }
                HorizontalDivider()
                Text("オープンソースでないもの(利用規約)", style = MaterialTheme.typography.titleSmall)
                NON_OPEN_SOURCE_COMPONENTS.forEach { (name, terms) -> LicenseEntry(name, terms) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

@Composable
private fun LicenseEntry(name: String, license: String) {
    Column {
        Text(name, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
        Text(
            license,
            style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** バックアップ・削除の実行中表示と、読み込み結果・エラーのダイアログ。 */
@Composable
internal fun DataOperationDialog(state: DataOperationState, onDismiss: () -> Unit) {
    when (state) {
        DataOperationState.Idle -> Unit
        is DataOperationState.Running -> AlertDialog(
            // 処理中は閉じられないようにする(結果が出たら自動で切り替わる)
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
                }
            },
            confirmButton = {}
        )
        is DataOperationState.Imported -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
            title = { Text("読み込みました") },
            text = { ImportSummaryText(state.summary) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        is DataOperationState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(state.title) },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium.japaneseParagraph()) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}

@Composable
private fun ImportSummaryText(summary: BackupImportSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SummaryRow("追加したブックマーク", "${summary.bookmarksAdded} 件")
        if (summary.bookmarksSkipped > 0) SummaryRow("登録済みでスキップ", "${summary.bookmarksSkipped} 件")
        SummaryRow("追加したタグ", "${summary.tagsAdded} 件")
        SummaryRow("追加した会話", "${summary.conversationsAdded} 件")
        if (summary.conversationsSkipped > 0) SummaryRow("登録済みの会話", "${summary.conversationsSkipped} 件")
        if (summary.invalidRecords > 0) SummaryRow("壊れていて読み飛ばした項目", "${summary.invalidRecords} 件")
        if (summary.bookmarksAdded > 0) {
            Spacer(Modifier.size(4.dp))
            Text(
                "検索用のデータと AI の要約は、裏で順に作り直します。",
                style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium.japaneseParagraph(), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.japaneseParagraph())
    }
}

/**
 * 端末内に残したクラッシュログの表示。外部には送らず、「共有」を押したときだけ、選んだ共有先に渡す。
 * 長いので、一番新しい1件を表示し、共有ではすべてをまとめて渡す。
 */
@Composable
internal fun CrashLogDialog(
    crashLog: CrashLogSummary,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("クラッシュログ") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "アプリが異常終了したときなどの記録です。端末の中にだけ保存され、自動では送信されません(最大 20 件まで、古いものから消えます)。不具合を報告するときに「共有」で渡せます。",
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph()
                )
                val latest = crashLog.latestText
                if (latest == null) {
                    Text("記録はありません。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    HorizontalDivider()
                    Text("${crashLog.count} 件(一番新しいものを表示)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        latest,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (crashLog.count > 0) {
                    TextButton(
                        onClick = onClear,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("削除") }
                    TextButton(onClick = onShare) { Text("共有") }
                }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    )
}
