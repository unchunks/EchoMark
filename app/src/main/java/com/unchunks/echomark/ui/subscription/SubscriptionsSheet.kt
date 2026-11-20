package com.unchunks.echomark.ui.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_RUN
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.ui.common.extractDomain
import com.unchunks.components.composeui.formatRelativeTime

/** 購読するフィードの管理シート。設定から開く。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsSheet(onDismiss: () -> Unit, viewModel: SubscriptionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SubscriptionsContent(
            state = state,
            onAdd = viewModel::add,
            onCheckNow = viewModel::checkNow,
            onSetEnabled = viewModel::setEnabled,
            onRemove = viewModel::remove,
            onDismissMessage = viewModel::dismissMessage
        )
    }
}

/** 購読の管理の中身(状態を受け取って描くだけなので、スクリーンショットテストで単体描画できる) */
@Composable
fun SubscriptionsContent(
    state: SubscriptionsUiState,
    onAdd: (input: String, onAdded: () -> Unit) -> Unit,
    onCheckNow: () -> Unit,
    onSetEnabled: (id: Long, enabled: Boolean) -> Unit,
    onRemove: (id: Long) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis()
) {
    var input by rememberSaveable { mutableStateOf("") }
    var removeTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "購読(RSS・YouTube)",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "ブログやニュースサイト、YouTube のチャンネルの新着を、ブックマークとして自動で保存します。" +
                "新着は毎晩(設定の夜間の時刻に)確認し、「充電中だけ」「Wi-Fi など従量課金でない接続のときだけ」の設定を守ります。" +
                "1回に保存するのは全体で最大 $MAX_NEW_ITEMS_PER_RUN 件(1つのフィードから $MAX_NEW_ITEMS_PER_FEED 件まで)で、" +
                "保存したものは1件ずつ順番にページを取得して要約します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("サイト・フィード・YouTube のチャンネルの URL") },
            placeholder = { Text("https://") },
            supportingText = { Text("サイトの URL を入れると、フィードを自動で探します") },
            singleLine = true,
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onAdd(input) { input = "" } },
                enabled = input.isNotBlank() && !state.isBusy
            ) { Text("購読する") }
            OutlinedButton(onClick = onCheckNow, enabled = state.subscriptions.any { it.enabled } && !state.isBusy) {
                Text("いま確認する")
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        if (state.isBusy) {
            Text(
                if (state.operation == SubscriptionOperation.Adding) "フィードを探しています…" else "新着を確認しています…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissMessage) { Text("閉じる") }
            }
        }

        HorizontalDivider()
        if (state.subscriptions.isEmpty()) {
            Text(
                "まだ購読していません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.subscriptions.forEach { subscription ->
                SubscriptionRow(
                    subscription = subscription,
                    nowMillis = nowMillis,
                    onSetEnabled = { onSetEnabled(subscription.id, it) },
                    onRemove = { removeTarget = subscription.id }
                )
            }
        }
    }

    removeTarget?.let { id ->
        val target = state.subscriptions.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("購読をやめますか?") },
            text = { Text("「${target?.title.orEmpty()}」の新着を保存しなくなります。これまでに保存したブックマークは残ります。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTarget = null
                        onRemove(id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("やめる") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    nowMillis: Long,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (subscription.type == SubscriptionType.YOUTUBE) Icons.Outlined.PlayCircle else Icons.Outlined.RssFeed,
            contentDescription = subscription.type.label,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(subscription.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = extractDomain(subscription.siteUrl ?: subscription.feedUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLabel(subscription, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = if (subscription.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = subscription.enabled,
            onCheckedChange = onSetEnabled,
            modifier = Modifier.semantics { contentDescription = "「${subscription.title}」を確認する" }
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "「${subscription.title}」の購読をやめる")
        }
    }
}

/** 購読の状態の一行(失敗していればその理由、まだ確認していなければその旨、そうでなければ最後に確認した時間) */
internal fun statusLabel(subscription: Subscription, nowMillis: Long): String = when {
    !subscription.enabled -> "オフ(確認しません)"
    subscription.lastError != null -> "確認できません: ${subscription.lastError}"
    subscription.lastCheckedAt == null -> "まだ確認していません(次の夜間に確認します)"
    else -> "最後の確認: ${formatRelativeTime(subscription.lastCheckedAt, nowMillis)}"
}
