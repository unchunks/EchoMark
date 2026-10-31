package com.unchunks.echomark.ui.snapshot

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.File

/**
 * ページのオフラインコピーを作っている間の画面。ページを実際に読み込む WebView を見せながら進み具合を出す
 * (見えない WebView は描かれず、PDF が空になる)。できたら [onCaptured]、作れなければ [onFailed]、
 * 利用者がやめたら [onCancel](一時ファイルも消す)。
 */
@Composable
internal fun PageSnapshotDialog(
    url: String,
    onCaptured: (SnapshotCapture) -> Unit,
    onFailed: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val webView = remember { WebView(context) }
    var step by remember { mutableStateOf(SnapshotStep.LOADING) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnFailed by rememberUpdatedState(onFailed)

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(url) {
        val dir = File(context.cacheDir, SNAPSHOT_CACHE_DIRECTORY)
        try {
            // 前の作りかけが残っていれば消す
            dir.listFiles()?.forEach { it.delete() }
            val capture = PageSnapshotter(webView, density).capture(url, dir) { step = it }
            currentOnCaptured(capture)
        } catch (e: CancellationException) {
            dir.listFiles()?.forEach { it.delete() }
            throw e
        } catch (e: SnapshotCaptureException) {
            currentOnFailed(e.message ?: "ページを保存できませんでした")
        } catch (e: Exception) {
            Timber.w(e, "オフラインコピーを作れなかった")
            currentOnFailed("ページを保存できませんでした")
        }
    }

    PageSnapshotProgress(url = url, step = step, onCancel = onCancel) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

/** 作っている間の画面の枠(状態を受け取って描くだけ)。[page] にページを読み込んでいる WebView を入れる */
@Composable
internal fun PageSnapshotProgress(
    url: String,
    step: SnapshotStep,
    onCancel: () -> Unit,
    page: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        PageSnapshotProgressContent(url = url, step = step, onCancel = onCancel, page = page)
    }
}

/** [PageSnapshotProgress] の中身(スクリーンショットテストで単体描画できる) */
@Composable
internal fun PageSnapshotProgressContent(
    url: String,
    step: SnapshotStep,
    onCancel: () -> Unit,
    page: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("オフライン用にページを保存しています", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stepLabel(step),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { page() }
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextButton(onClick = onCancel) { Text("やめる") }
            }
        }
    }
}

internal fun stepLabel(step: SnapshotStep): String = when (step) {
    SnapshotStep.LOADING -> "ページを読み込んでいます…"
    SnapshotStep.SAVING_PAGE -> "ページを保存しています…"
    SnapshotStep.MAKING_PDF -> "PDF を作っています…(画面は動かさないでください)"
}

/** 作りかけの一時ファイルを置く cacheDir 内のディレクトリ名 */
internal const val SNAPSHOT_CACHE_DIRECTORY = "snapshots"
