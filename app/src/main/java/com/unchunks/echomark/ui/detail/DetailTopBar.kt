package com.unchunks.echomark.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.ui.components.FavoriteButton
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailTopBar(
    bookmark: Bookmark?,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    callbacks: BookmarkDetailCallbacks,
    showBackButton: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** 読書モードを開く。本文が無いときは null(ボタンを出さない) */
    onReadingMode: (() -> Unit)?,
    onAddToCollection: () -> Unit,
    onExport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    EchoMarkTopAppBar(
        title = {},
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = callbacks.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            }
        },
        scrollBehavior = scrollBehavior,
        actions = {
            if (bookmark == null) return@EchoMarkTopAppBar
            if (onReadingMode != null) {
                IconButton(onClick = onReadingMode) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = "読書モード")
                }
            }
            FavoriteButton(isFavorite = bookmark.isFavorite, onClick = callbacks.onToggleFavorite)
            IconButton(onClick = { callbacks.onShare(bookmark) }) {
                Icon(Icons.Outlined.Share, contentDescription = "共有")
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "その他のメニュー")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("編集") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (bookmark.isRead) "未読に戻す" else "既読にする") },
                    leadingIcon = {
                        Icon(if (bookmark.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.TaskAlt, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        callbacks.onToggleRead()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (bookmark.isArchived) "アーカイブから戻す" else "アーカイブ") },
                    leadingIcon = {
                        Icon(
                            if (bookmark.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuOpen = false
                        callbacks.onSetArchived(!bookmark.isArchived)
                    }
                )
                DropdownMenuItem(
                    text = { Text("AIで処理し直す") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.onReprocess()
                    }
                )
                // 要約が済んでいれば、要約だけ・タグだけを作り直せる(もう片方はそのまま)
                if (bookmark.aiStatus == AiStatus.DONE && !bookmark.summary.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text("要約だけ作り直す") },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            callbacks.onReprocessPart(AnalysisScope.SUMMARY)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("タグだけ付け直す") },
                        leadingIcon = { Icon(Icons.Outlined.Sell, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            callbacks.onReprocessPart(AnalysisScope.TAGS)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("コレクションに追加") },
                    leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onAddToCollection()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (bookmark.isInReadingList) "「あとで読む」から外す" else "あとで読む") },
                    leadingIcon = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.readLater.onToggleReadingList(bookmark)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (callbacks.readLater.isInReview) "復習から外す" else "復習に入れる") },
                    leadingIcon = { Icon(Icons.Outlined.Quiz, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.readLater.onToggleReview(bookmark)
                    }
                )
                DropdownMenuItem(
                    text = { Text("ポッドキャストにする") },
                    leadingIcon = { Icon(Icons.Outlined.Podcasts, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.onMakePodcast(bookmark.id)
                    }
                )
                DropdownMenuItem(
                    text = { Text("ホーム画面に追加") },
                    leadingIcon = { Icon(Icons.Outlined.AddToHomeScreen, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        callbacks.onPinShortcut(bookmark)
                    }
                )
                DropdownMenuItem(
                    text = { Text("ファイルに書き出す") },
                    leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onExport()
                    }
                )
                DropdownMenuItem(
                    text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    )
}
