package com.unchunks.echomark.ui.snapshot

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存したオフラインコピーを開く画面の表示([SnapshotViewerActivity] が使う)。状態を受け取って描くだけなので、
 * スクリーンショットテストでは [page] に仮の中身を入れて単体描画できる。
 * 上に「オフラインコピー」であることと保存日時を出し、PDF があれば開く・元のページをブラウザで開くボタンを付ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnapshotViewerContent(
    state: SnapshotViewerState,
    onBack: () -> Unit,
    onOpenPdf: () -> Unit,
    onOpenOriginal: () -> Unit,
    page: @Composable (SnapshotViewerState.Ready) -> Unit
) {
    val ready = state as? SnapshotViewerState.Ready
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(ready?.title ?: "オフラインコピー", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    if (ready?.hasPdf == true) {
                        IconButton(onClick = onOpenPdf) { Icon(Icons.Outlined.PictureAsPdf, contentDescription = "PDF を開く") }
                    }
                    if (ready?.originalUrl != null) {
                        IconButton(onClick = onOpenOriginal) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "元のページをブラウザで開く")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (state) {
                SnapshotViewerState.Loading -> Unit
                SnapshotViewerState.Missing -> Text(
                    "オフラインコピーが見つかりません。詳細画面から保存し直してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp)
                )
                is SnapshotViewerState.Ready -> {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "オフラインコピー(${formatDate(state.savedAt)}に保存)。リンクを押すとブラウザで開きます",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    page(state)
                }
            }
        }
    }
}

/**
 * オフラインコピーを表示する WebView。保存した MHTML のファイルだけを読み、通信とページのスクリプトは使わない。
 *
 * - JavaScript は止める(保存したページのスクリプトを動かさない)
 * - ネットワークを禁止する(MHTML に入っていない画像・スクリプトなどを、開き直したときに外から読み込まない。MHTML の中の内容は読める)
 * - ファイルは、この MHTML 以外の file:// を読ませない([isFileRequestAllowed])。ほかのファイルの内容を読む手段も無効にする
 * - ページの中のリンクは、WebView の中では開かず、利用者が押した http / https だけをブラウザに渡す([linkActionFor])
 */
@Composable
internal fun SnapshotWebView(fileUrl: String, onOpenInBrowser: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> createSnapshotWebView(context, fileUrl, onOpenInBrowser) },
        onRelease = { it.stopLoading(); it.destroy() }
    )
}

internal fun createSnapshotWebView(context: Context, fileUrl: String, onOpenInBrowser: (String) -> Unit): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = false
            // MHTML をファイルから読むために必要。ほかのファイルへのアクセス(下の 2 つ)と、通信は許さない
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_CACHE_ONLY
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            // ページを画面の幅に合わせて見せ、つまんで拡大できるようにする
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // ページの中の見出しへの移動(#...)は、この画面の中で動かす
                if (url.toString().substringBefore('#') == fileUrl.substringBefore('#')) return false
                if (linkActionFor(url.scheme, request.hasGesture()) == SnapshotLinkAction.OPEN_IN_BROWSER) {
                    onOpenInBrowser(url.toString())
                }
                // どちらの場合も、WebView の中では読み込まない
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (isFileRequestAllowed(request.url.toString(), fileUrl)) null else forbidden()
        }
        loadUrl(fileUrl)
    }

private fun forbidden() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun formatDate(millis: Long): String = SimpleDateFormat("yyyy年M月d日 H:mm", Locale.JAPAN).format(Date(millis))
