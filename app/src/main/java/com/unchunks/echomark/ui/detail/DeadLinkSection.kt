package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.linkcheck.LinkCheckState

/** Internet Archive(Wayback Machine)で [url] の保存を探すページの URL */
internal fun archiveSearchUrl(url: String): String = "https://web.archive.org/web/${url.trim()}"

/**
 * リンク切れの知らせ(#94)。続けて開けなかったと判断したリンク([state] が切れている)だけに出す。
 * 端末にオフラインコピー(#77)があれば、まずそれを開くよう勧める。無ければ Internet Archive で探せる。
 * 「もう一度確認」で今すぐ確かめ直せ、「確認しない」で夜の確認の対象から外せる。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeadLinkSection(
    state: LinkCheckState,
    hasOfflineCopy: Boolean,
    checking: Boolean,
    onOpenOfflineCopy: () -> Unit,
    onSearchArchive: () -> Unit,
    onRecheck: () -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                Text(
                    text = "このリンクは開けないようです",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = deadLinkDetail(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (hasOfflineCopy) {
                Text(
                    text = "保存したオフラインコピーで、ページの中身を読めます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasOfflineCopy) {
                    Button(onClick = onOpenOfflineCopy) { Text("オフラインコピーを開く") }
                } else {
                    OutlinedButton(onClick = onSearchArchive) { Text("Internet Archive で探す") }
                }
                OutlinedButton(onClick = onRecheck, enabled = !checking) { Text(if (checking) "確認中…" else "もう一度確認") }
                TextButton(onClick = onMute) { Text("確認しない") }
            }
        }
    }
}

/** 「3回続けて開けませんでした(ページが見つかりません(HTTP 404))」のような説明 */
internal fun deadLinkDetail(state: LinkCheckState): String {
    val reason = state.lastReason?.takeIf { it.isNotBlank() }?.let { "(${it})" }.orEmpty()
    return "夜の確認で${state.failureCount}回続けて開けませんでした$reason。一時的な不具合なら、あとで開けるようになることもあります。"
}
