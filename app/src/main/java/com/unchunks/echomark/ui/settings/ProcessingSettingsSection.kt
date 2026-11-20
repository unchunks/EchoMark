package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.ui.components.SectionHeader

/*
 * 設定画面の「処理」の欄(#8 充電中だけ、#9 夜間にまとめて、#70 同時に動かす数・クラウド API の呼び出しの上限)。
 */

/** 「処理」の欄の行。値を選ぶ行は [onOpenDialog] でダイアログを開く。 */
internal fun LazyListScope.processingSettingsItems(
    settings: ProcessingSettings,
    onChange: ((ProcessingSettings) -> ProcessingSettings) -> Unit,
    onOpenDialog: (SettingsDialog) -> Unit,
    onOpenQueue: () -> Unit = {}
) {
    item { SectionHeader("処理") }
    item {
        SettingsItem(
            title = "処理の状況",
            icon = Icons.Outlined.Pending,
            summary = "処理中・処理待ちのもの、最近失敗したものを見ます",
            onClick = onOpenQueue
        )
    }
    item {
        SettingsSwitchItem(
            title = "充電中だけ処理する",
            icon = Icons.Outlined.BatteryChargingFull,
            summary = "文字起こしや AI の要約・タグ付けを、充電しているときだけ行います",
            checked = settings.chargingOnly,
            onCheckedChange = { checked -> onChange { it.copy(chargingOnly = checked) } }
        )
    }
    item {
        SettingsSwitchItem(
            title = "Wi-Fi のときだけ通信する",
            icon = Icons.Outlined.Wifi,
            summary = "リンク先の本文の取得を、Wi-Fi など通信量を気にしなくてよい接続のときだけ行います",
            checked = settings.unmeteredOnly,
            onCheckedChange = { checked -> onChange { it.copy(unmeteredOnly = checked) } }
        )
    }
    item {
        SettingsSwitchItem(
            title = "夜間にまとめて処理する",
            icon = Icons.Outlined.Bedtime,
            summary = "保存したものはすぐには処理せず、夜の決まった時刻から充電中にまとめて処理します。" +
                "詳細画面の「再試行」はすぐに行います",
            checked = settings.nightlyBatch,
            onCheckedChange = { checked -> onChange { it.copy(nightlyBatch = checked) } }
        )
    }
    if (settings.nightlyBatch) {
        item {
            SettingsItem(
                title = "夜間の処理を始める時刻",
                icon = Icons.Outlined.Schedule,
                summary = nightlyHourLabel(settings.nightlyHour),
                onClick = { onOpenDialog(SettingsDialog.NIGHTLY_HOUR) }
            )
        }
    }
    item {
        SettingsItem(
            title = "同時に処理する数",
            icon = Icons.Outlined.Tune,
            summary = concurrencyLabel(settings.maxConcurrent),
            onClick = { onOpenDialog(SettingsDialog.CONCURRENCY) }
        )
    }
    // クラウドの AI を無効にしている間は、呼び出しの上限も出さない(CLOUD_AI_ENABLED)
    if (CLOUD_AI_ENABLED) {
        item {
            SettingsItem(
                title = "クラウド AI の呼び出しの上限",
                icon = Icons.Outlined.Speed,
                summary = apiRateLabel(settings.apiRequestsPerMinute),
                onClick = { onOpenDialog(SettingsDialog.API_RATE) }
            )
        }
    }
}

/** 「処理」の欄から開くダイアログ。該当しなければ何も出さない。 */
@Composable
internal fun ProcessingSettingsDialog(
    dialog: SettingsDialog,
    settings: ProcessingSettings,
    onChange: ((ProcessingSettings) -> ProcessingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    when (dialog) {
        SettingsDialog.NIGHTLY_HOUR -> ChoiceDialog(
            title = "夜間の処理を始める時刻",
            options = NIGHTLY_HOUR_CHOICES,
            selected = settings.nightlyHour,
            label = ::nightlyHourLabel,
            onSelect = { hour ->
                onChange { it.copy(nightlyHour = hour) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        SettingsDialog.CONCURRENCY -> ChoiceDialog(
            title = "同時に処理する数",
            options = (1..ProcessingSettings.MAX_CONCURRENT_LIMIT).toList(),
            selected = settings.maxConcurrent,
            label = { count ->
                when (count) {
                    1 -> "1 件ずつ(端末への負担が最も少ない)"
                    ProcessingSettings.DEFAULT_MAX_CONCURRENT -> "$count 件まで(標準)"
                    else -> "$count 件まで"
                }
            },
            onSelect = { count ->
                onChange { it.copy(maxConcurrent = count) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        SettingsDialog.API_RATE -> ChoiceDialog(
            title = "クラウド AI の呼び出しの上限",
            options = ProcessingSettings.API_RATE_CHOICES,
            selected = settings.apiRequestsPerMinute,
            label = ::apiRateLabel,
            onSelect = { rate ->
                onChange { it.copy(apiRequestsPerMinute = rate) }
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> Unit
    }
}

/** 夜間の処理を始める時刻として選べる時(夜〜明け方) */
private val NIGHTLY_HOUR_CHOICES = listOf(22, 23, 0, 1, 2, 3, 4, 5)

internal fun nightlyHourLabel(hour: Int): String = "%d:00".format(hour)

internal fun concurrencyLabel(count: Int): String = if (count <= 1) "1 件ずつ" else "$count 件まで"

internal fun apiRateLabel(perMinute: Int): String = if (perMinute <= 0) "制限なし" else "1 分あたり $perMinute 回まで"
