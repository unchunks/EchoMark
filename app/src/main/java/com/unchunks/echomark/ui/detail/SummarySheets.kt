package com.unchunks.echomark.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.model.SummarySource
import com.unchunks.echomark.domain.model.SummaryVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * 要約を手で直すシートと、要約の履歴を見比べるシート(#19・#20)。
 */

/** 要約を手で直すシート。保存すると [onSave] に新しい要約を渡す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryEditSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SummaryEditSheetContent(text = text, onTextChange = { text = it }, onCancel = onDismiss, onSave = { onSave(text) })
    }
}

/** 要約を直すシートの中身(状態を受け取って描くだけ) */
@Composable
fun SummaryEditSheetContent(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("要約を直す", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
            label = { Text("要約") },
            minLines = 4,
            isError = text.isBlank(),
            supportingText = {
                Text(
                    if (text.isBlank()) {
                        "要約を入力してください"
                    } else {
                        "直した要約は、「AIで処理し直す」では置き換わりません。前の要約は履歴に残ります"
                    }
                )
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("キャンセル") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onSave, enabled = text.isNotBlank()) { Text("保存") }
        }
    }
}

/** 要約の履歴のシート。版を選んで、今の要約と見比べたり、その版に戻したりできる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryHistorySheet(
    current: String,
    versions: List<SummaryVersion>,
    onDismiss: () -> Unit,
    onRestore: (Long) -> Unit
) {
    var compareId by rememberSaveable { mutableStateOf<Long?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SummaryHistorySheetContent(
            current = current,
            versions = versions,
            compareId = compareId,
            onCompare = { compareId = it },
            onRestore = {
                compareId = null
                onRestore(it)
                onDismiss()
            }
        )
    }
}

/**
 * 要約の履歴の中身(状態を受け取って描くだけ)。新しい版から先に並べ、今の要約と同じ版には「現在」を付ける。
 * [compareId] の版を選んでいるときは、先頭に今の要約とその版を並べて見比べられる。
 */
@Composable
fun SummaryHistorySheetContent(
    current: String,
    versions: List<SummaryVersion>,
    compareId: Long?,
    onCompare: (Long?) -> Unit,
    onRestore: (Long) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val compared = versions.firstOrNull { it.id == compareId }
    // 今の要約と同じ文の版のうち、いちばん新しい1つを「現在」にする(同じ文を直し直したときに2つ付けない)
    val currentId = versions.firstOrNull { it.summary == current }?.id
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("要約の履歴", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            "作り直したり直したりした要約を、新しい順に残しています。見比べて、気に入った版に戻せます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (compared != null) {
            CompareBlock(label = "現在の要約", text = current, emphasized = false)
            CompareBlock(label = "選んだ版(${versionTitle(compared, zone)})", text = compared.summary, emphasized = true)
            TextButton(onClick = { onCompare(null) }) { Text("見比べを閉じる") }
        }
        versions.forEach { version ->
            VersionCard(
                version = version,
                isCurrent = version.id == currentId,
                isCompared = version.id == compareId,
                zone = zone,
                onCompare = { onCompare(version.id) },
                onRestore = { onRestore(version.id) }
            )
        }
    }
}

@Composable
private fun CompareBlock(label: String, text: String, emphasized: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VersionCard(
    version: SummaryVersion,
    isCurrent: Boolean,
    isCompared: Boolean,
    zone: ZoneId,
    onCompare: () -> Unit,
    onRestore: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    versionTitle(version, zone),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrent) {
                    Text("現在", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            versionDetail(version)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(version.summary, style = MaterialTheme.typography.bodyMedium)
            if (!isCurrent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCompare, enabled = !isCompared) {
                        Text(if (isCompared) "見比べ中" else "現在と見比べる")
                    }
                    Button(onClick = onRestore) { Text("この版に戻す") }
                }
            }
        }
    }
}

/** 版の見出し(「5月3日 14:20 ・ AI」「5月3日 14:20 ・ 手で編集」) */
internal fun versionTitle(version: SummaryVersion, zone: ZoneId): String =
    "${formatVersionTime(version.createdAt, zone)} ・ ${version.source.label}"

/** 版の補足(作った AI と、そのときの言語・書き方)。分からなければ null */
internal fun versionDetail(version: SummaryVersion): String? =
    listOfNotNull(
        version.generator.takeIf { version.source == SummarySource.AI },
        version.style
    ).takeIf { it.isNotEmpty() }?.joinToString(" ・ ")

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 H:mm", Locale.JAPAN)

internal fun formatVersionTime(millis: Long, zone: ZoneId): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))
