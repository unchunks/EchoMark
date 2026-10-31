package com.unchunks.echomark.ui.snapshot

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ページのオフラインコピーを作れなかった */
internal class SnapshotCaptureException(message: String) : Exception(message)

/** 作ったオフラインコピーの一時ファイル。[pdf] は作れなかったとき null */
internal class SnapshotCapture(val mhtml: File, val pdf: File?)

/** 作っている途中の段 */
internal enum class SnapshotStep { LOADING, SAVING_PAGE, MAKING_PDF }

/**
 * 画面に出している [webView](メインスレッド)でページを読み込み、オフラインコピー(MHTML と PDF)の一時ファイルを作る。
 *
 * - MHTML: [WebView.saveWebArchive]。ページとその画像・CSS を1つのファイルにまとめる。読み込み時の JavaScript で描かれた内容も入る
 * - PDF: [PdfDocument] に、WebView の表示を縦に区切って1ページずつ描く。`android.print` のコールバックを継ぐ方法は使わない。
 *   PDF は補助で、描けなかった・何も描かれていないときは作らず(MHTML だけで)続ける
 *
 * 利用者に見える画面で動かす(見えていない WebView は描かれず、PDF が空になる)。ページのスクリプトは読み込み中だけ動かし、
 * 保存したコピーを開くときは動かさない([SnapshotViewerActivity])。
 */
internal class PageSnapshotter(private val webView: WebView, private val density: Float) {

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        webView.settings.apply {
            // 読み込み時の JavaScript で描かれる内容(SPA など)も入れるため、読み込み中だけ有効にする
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            // WebView 内の保存済みのパスワード・フォームの入力は使わない
            @Suppress("DEPRECATION")
            saveFormData = false
        }
        // ダウンロードは受け取らない
        webView.setDownloadListener { _, _, _, _, _ -> }
        // 保存中に画面を触って、ページの中のリンクを押してしまわないようにする
        webView.setOnTouchListener { _, _ -> true }
    }

    /**
     * [url] のオフラインコピーを [dir] に作る。
     * 読み込めない・保存できないときは [SnapshotCaptureException]。呼び出し元のコルーチンを取り消すと読み込みを止める。
     */
    suspend fun capture(url: String, dir: File, onStep: (SnapshotStep) -> Unit): SnapshotCapture {
        dir.mkdirs()
        onStep(SnapshotStep.LOADING)
        load(url)
        // 読み込みが終わった後に描かれる内容を少し待つ
        delay(SETTLE_MILLIS)

        onStep(SnapshotStep.SAVING_PAGE)
        val mhtml = File(dir, "page.mhtml").also { it.delete() }
        if (!saveArchive(mhtml)) throw SnapshotCaptureException("ページを保存できませんでした")

        onStep(SnapshotStep.MAKING_PDF)
        val pdfFile = File(dir, "page.pdf").also { it.delete() }
        val pdf = try {
            if (makePdf(pdfFile)) pdfFile else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "PDF を作れなかった")
            null
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "PDF を作るメモリが足りなかった")
            null
        }
        if (pdf == null) pdfFile.delete()
        return SnapshotCapture(mhtml, pdf)
    }

    private suspend fun load(url: String) {
        try {
            withTimeout(LOAD_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            if (continuation.isActive && finishedUrl != "about:blank") continuation.resume(Unit)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && continuation.isActive) {
                                continuation.resumeWithException(SnapshotCaptureException("ページを読み込めませんでした"))
                            }
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (request.isForMainFrame && errorResponse.statusCode >= HTTP_ERROR_STATUS && continuation.isActive) {
                                continuation.resumeWithException(SnapshotCaptureException("ページを読み込めませんでした(${errorResponse.statusCode})"))
                            }
                        }

                        // http / https 以外(アプリを開く intent: など)には移らない
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.scheme?.lowercase() !in WEB_SCHEMES
                    }
                    continuation.invokeOnCancellation { webView.stopLoading() }
                    webView.loadUrl(url)
                }
            }
        } catch (e: TimeoutCancellationException) {
            webView.stopLoading()
            throw SnapshotCaptureException("ページの読み込みに時間がかかりすぎました")
        }
    }

    private suspend fun saveArchive(target: File): Boolean = suspendCancellableCoroutine { continuation ->
        webView.saveWebArchive(target.path, false) { saved ->
            if (continuation.isActive) continuation.resume(saved != null && target.length() > 0)
        }
    }

    /**
     * 表示を縦に区切って PDF にする。表示の幅 × 高さを1ページとし、スクロールしながら1ページずつ描く。
     * 先に1ページ目を小さなビットマップに描いて、何も描かれないとき(描画先によっては空になる)は作らずに false を返す。
     */
    private suspend fun makePdf(target: File): Boolean {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) return false
        val contentHeight = (webView.contentHeight * density).toInt()
        val offsets = pdfPageOffsets(contentHeight, height)
        if (offsets.isEmpty()) return false

        webView.scrollTo(0, 0)
        delay(FRAME_WAIT_MILLIS)
        if (isBlankDraw(width, height)) {
            Timber.w("PageSnapshotter: WebView の描画結果が空なので PDF を作らない")
            return false
        }

        val document = PdfDocument()
        try {
            offsets.forEachIndexed { index, offset ->
                webView.scrollTo(0, offset)
                delay(FRAME_WAIT_MILLIS)
                // 最後のページなどでスクロールが端で止まったときの、ずれの分だけ描く位置を戻す
                val shift = (offset - webView.scrollY).coerceAtLeast(0)
                val pageHeight = minOf(height, contentHeight - offset).coerceAtLeast(1)
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, pageHeight, index + 1).create())
                page.canvas.translate(0f, -shift.toFloat())
                webView.draw(page.canvas)
                document.finishPage(page)
            }
            target.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
            webView.scrollTo(0, 0)
        }
        return target.length() > 0
    }

    /** 1ページ目を縮小したビットマップに描いて、何も描かれていないか調べる */
    private fun isBlankDraw(width: Int, height: Int): Boolean {
        val probeWidth = (width * PROBE_SCALE).toInt().coerceAtLeast(1)
        val probeHeight = (height * PROBE_SCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(probeWidth, probeHeight, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.scale(PROBE_SCALE, PROBE_SCALE)
            webView.draw(canvas)
            val pixels = IntArray(probeWidth * probeHeight)
            bitmap.getPixels(pixels, 0, probeWidth, 0, 0, probeWidth, probeHeight)
            isBlankImage(pixels, probeWidth, probeHeight)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
        const val HTTP_ERROR_STATUS = 400
        const val PROBE_SCALE = 0.25f
    }
}
