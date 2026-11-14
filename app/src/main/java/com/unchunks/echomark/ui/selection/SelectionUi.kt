package com.unchunks.echomark.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.bookmark.model.Bookmark

/**
 * 複数選択の間、通常のトップバーの代わりに出すバー。選んでいる件数と、すべて選ぶ・選びを解く・選択をやめるを出す。
 * 件数が変わると、TalkBack が読み上げる。
 *
 * @param count 選んでいる件数
 * @param total 選べる件数(今の一覧に出ている数)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        // 文字が大きいときも、1行に収めて省略する
        title = {
            Text(
                text = if (count == 0) "項目を選んでください" else "${count}件を選択中",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "選択をやめる")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, enabled = count < total) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "すべて選択")
            }
            IconButton(onClick = onClear, enabled = count > 0) {
                Icon(Icons.Outlined.Deselect, contentDescription = "選択を解除")
            }
        },
        modifier = modifier,
        scrollBehavior = scrollBehavior
    )
}

/** カードの角に重ねる、選んでいるかの丸いしるし(飾り。選んだかは、カードの「選択済み」の状態で読み上げる) */
@Composable
fun SelectionMark(checked: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (checked) scheme.primary else scheme.surface.copy(alpha = 0.9f))
            .border(2.dp, if (checked) scheme.primary else scheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * 複数選択の操作の結果を Snackbar で知らせる。アーカイブ・削除・コレクションから外したときは「元に戻す」を付け、
 * 押されたら対応する取り消しを呼ぶ。
 */
suspend fun SnackbarHostState.showBulkMessage(
    message: BulkMessage,
    onUndoArchive: (List<Bookmark>) -> Unit,
    onUndoDelete: (List<Bookmark>) -> Unit,
    onUndoRemoveFromCollection: (collectionId: Long, List<Bookmark>) -> Unit = { _, _ -> }
) {
    when (message) {
        is BulkMessage.Done -> showSnackbar(message.text)
        is BulkMessage.Failed -> showSnackbar(message.text)
        is BulkMessage.Archived -> {
            val text = if (message.archived) "${message.before.size}件をアーカイブしました" else "${message.before.size}件をアーカイブから戻しました"
            if (showSnackbar(text, actionLabel = "元に戻す", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                onUndoArchive(message.before)
            }
        }
        is BulkMessage.Deleted -> {
            if (showSnackbar("${message.bookmarks.size}件を削除しました", actionLabel = "元に戻す", duration = SnackbarDuration.Long) ==
                SnackbarResult.ActionPerformed
            ) {
                onUndoDelete(message.bookmarks)
            }
        }
        is BulkMessage.RemovedFromCollection -> {
            if (showSnackbar("${message.bookmarks.size}件をコレクションから外しました", actionLabel = "元に戻す", duration = SnackbarDuration.Long) ==
                SnackbarResult.ActionPerformed
            ) {
                onUndoRemoveFromCollection(message.collectionId, message.bookmarks)
            }
        }
    }
}
