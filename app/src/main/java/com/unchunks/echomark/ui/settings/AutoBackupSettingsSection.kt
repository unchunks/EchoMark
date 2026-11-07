package com.unchunks.echomark.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.unchunks.echomark.domain.backup.AutoBackupSettings
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 設定画面の「データ」の中の、自動バックアップの欄。毎晩、利用者が選んだフォルダに書き出す
 * (処理の設定の「充電中だけ」に従う)。保存先を選ぶ前はスイッチだけを出す。
 */
internal fun LazyListScope.autoBackupItems(
    settings: AutoBackupSettings,
    onToggle: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onOpenKeepDialog: () -> Unit,
    onIncludeFilesChange: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    item {
        SettingsSwitchItem(
            title = "自動バックアップ",
            icon = Icons.Outlined.Backup,
            summary = autoBackupSummary(settings, zone),
            checked = settings.enabled,
            onCheckedChange = onToggle
        )
    }
    if (!settings.enabled) return
    settings.lastError?.let { error ->
        item {
            SettingsNotice(
                text = error,
                icon = Icons.Outlined.WarningAmber,
                isWarning = true,
                action = { TextButton(onClick = onPickFolder) { Text("保存先を選ぶ") } }
            )
        }
    }
    item {
        SettingsItem(
            title = "保存先のフォルダ",
            icon = Icons.Outlined.Folder,
            summary = settings.folderLabel ?: "選んでいません",
            onClick = onPickFolder
        )
    }
    item {
        SettingsItem(
            title = "残すバックアップの数",
            icon = Icons.Outlined.History,
            summary = "新しい ${settings.keep} 個を残し、古いものから消します(自動バックアップで作ったものだけ)",
            onClick = onOpenKeepDialog
        )
    }
    item {
        SettingsSwitchItem(
            title = "ファイルも含める",
            icon = Icons.Outlined.AttachFile,
            summary = "画像・PDF・音声・動画と、オフラインコピーも入れた zip にします。大きくなることがあります",
            checked = settings.includeFiles,
            onCheckedChange = onIncludeFilesChange
        )
    }
    item {
        SettingsItem(
            title = "今すぐバックアップ",
            icon = Icons.Outlined.PlayArrow,
            summary = "夜を待たずに、今のデータを保存先に書き出します",
            onClick = onRunNow
        )
    }
}

/** スイッチの説明。最後に書き出した日時があれば添える */
internal fun autoBackupSummary(settings: AutoBackupSettings, zone: ZoneId = ZoneId.systemDefault()): String {
    if (!settings.enabled) return "毎晩、選んだフォルダにバックアップを保存します(充電中だけの設定に従います)"
    val base = "毎晩、「${settings.folderLabel ?: "選んだフォルダ"}」に保存します"
    val last = settings.lastSuccessAt?.let { "。最後のバックアップ: ${LAST_TIME.format(Instant.ofEpochMilli(it).atZone(zone))}" } ?: "。まだ保存していません"
    return base + last
}

private val LAST_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 H:mm")

/** フォルダを選んだあと、読み書きの許可を持ち続ける。持てなければ false */
internal fun takeFolderPermission(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
    true
} catch (e: SecurityException) {
    Timber.w(e, "保存先のフォルダの許可を持てない")
    false
}

/** 前に選んでいたフォルダの許可を手放す(フォルダを選び直したとき) */
internal fun releaseFolderPermission(context: Context, uri: String?) {
    if (uri == null) return
    try {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (e: SecurityException) {
        // もう持っていない
    }
}

/** 画面に出すフォルダの名前(読めなければ、URI の中の場所) */
internal fun folderLabelOf(context: Context, uri: Uri): String {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    val name = documentId?.let { id ->
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }
    return name ?: documentId?.substringAfter(':')?.ifBlank { null } ?: "選んだフォルダ"
}
