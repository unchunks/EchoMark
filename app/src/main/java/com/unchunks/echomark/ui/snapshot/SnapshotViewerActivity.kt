package com.unchunks.echomark.ui.snapshot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.FileProvider
import com.unchunks.echomark.MainUiState
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.isDark
import com.unchunks.echomark.data.attachment.attachmentFile
import com.unchunks.echomark.ui.common.openUrl
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * 保存したページのオフラインコピー(MHTML)を、通信せずに開く画面。アプリの外からは起動できない(exported=false)。
 * 表示は [SnapshotWebView] で、JavaScript・通信・ほかのファイルの読み込みを許さない設定にしている。
 */
@AndroidEntryPoint
internal class SnapshotViewerActivity : ComponentActivity() {

    private val viewModel: SnapshotViewerViewModel by viewModels()

    /** テーマ設定の読み込み(アプリ本体と同じ) */
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // アプリのテーマ設定に合わせる。読み込み前(ほんの一瞬)は何も描かない
            val appState by appViewModel.uiState.collectAsState()
            val ready = appState as? MainUiState.Ready ?: return@setContent
            EchoMarkTheme(darkTheme = ready.themeMode.isDark(isSystemInDarkTheme()), dynamicColor = ready.dynamicColor) {
                val state by viewModel.state.collectAsState()
                val context = LocalContext.current
                SnapshotViewerContent(
                    state = state,
                    onBack = { finish() },
                    onOpenPdf = { (state as? SnapshotViewerState.Ready)?.let { openSnapshotPdf(context, it.pdfPath) } },
                    onOpenOriginal = { (state as? SnapshotViewerState.Ready)?.originalUrl?.let { openUrl(context, it) } }
                ) { page ->
                    SnapshotWebView(
                        fileUrl = page.fileUrl,
                        onOpenInBrowser = { openUrl(context, it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    companion object {
        /** [bookmarkId] のオフラインコピーを開く画面の Intent */
        fun intent(context: Context, bookmarkId: Long): Intent =
            Intent(context, SnapshotViewerActivity::class.java).putExtra(EXTRA_SNAPSHOT_BOOKMARK_ID, bookmarkId)
    }
}

/** [pdfPath](filesDir からの相対パス)の PDF を、ほかのアプリで開く */
internal fun openSnapshotPdf(context: Context, pdfPath: String?) {
    val file = attachmentFile(context.filesDir, pdfPath)?.takeIf { it.isFile }
    if (file == null) {
        Toast.makeText(context, "PDF がこの端末にありません", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "PDF の URI を作れない")
        Toast.makeText(context, "PDF を開けませんでした", Toast.LENGTH_SHORT).show()
        return
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "PDF を開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/** 詳細画面から PDF を開く */
internal fun openSnapshotPdf(context: Context, bookmark: Bookmark) = openSnapshotPdf(context, bookmark.snapshotPdfPath)
