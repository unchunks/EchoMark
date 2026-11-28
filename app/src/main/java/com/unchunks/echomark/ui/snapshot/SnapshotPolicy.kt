package com.unchunks.echomark.ui.snapshot

import kotlin.math.max
import kotlin.math.min

/*
 * ページのオフラインコピーの、Android に依存しない決まりごと(単体テストでも使う)。
 */

/** オフラインコピーを作れるページか(http / https のリンクだけ。ファイルやメモ、ほかのスキームは対象外) */
internal fun canSnapshot(url: String?): Boolean {
    val lower = url?.trim()?.lowercase() ?: return false
    return lower.startsWith("http://") || lower.startsWith("https://")
}

/** 保存したオフラインコピーの中のリンクを押したときの扱い */
internal enum class SnapshotLinkAction {
    /** 何もしない(コピーの中では読み込まない) */
    BLOCK,

    /** ブラウザで開く(利用者が押したときだけ) */
    OPEN_IN_BROWSER
}

/**
 * オフラインコピーを表示している WebView で、遷移の要求をどうするか。
 * コピーを開き直したときに、元のページや外部の内容を読み込まないために、WebView の中では何も読み込まず、
 * http / https のリンクを利用者が押したときだけブラウザに渡す。
 */
internal fun linkActionFor(scheme: String?, isUserGesture: Boolean): SnapshotLinkAction =
    if (isUserGesture && (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))) {
        SnapshotLinkAction.OPEN_IN_BROWSER
    } else {
        SnapshotLinkAction.BLOCK
    }

/**
 * オフラインコピーを表示している WebView が読み込んでよいリソースか。保存した MHTML のファイル([allowedFileUrl])自体だけを許し、
 * ほかの file:// (このアプリの内部ファイルなど)は読ませない。http / https は WebView の設定(ネットワークの禁止)で止める。
 */
internal fun isFileRequestAllowed(requestUrl: String, allowedFileUrl: String): Boolean {
    if (!requestUrl.startsWith("file:", ignoreCase = true)) return true
    return requestUrl == allowedFileUrl
}

/**
 * PDF にするときの、1ページ分ずつの縦の位置の一覧。ページの高さ [pageHeight] で [contentHeight] の全体を上から区切る。
 * 長すぎるページは [maxPages] ページまでで打ち切る。[pageHeight] や [contentHeight] が 0 以下なら空。
 */
internal fun pdfPageOffsets(contentHeight: Int, pageHeight: Int, maxPages: Int = MAX_PDF_PAGES): List<Int> {
    if (contentHeight <= 0 || pageHeight <= 0) return emptyList()
    val pages = min((contentHeight + pageHeight - 1) / pageHeight, max(1, maxPages))
    return List(pages) { it * pageHeight }
}

/** PDF のページ数の上限(非常に長いページで時間とメモリを使いすぎないため) */
internal const val MAX_PDF_PAGES = 40

/**
 * 画面の描画結果が「何も描かれていない(ほぼ1色)」か。[pixels] は [width] x [height] の ARGB を並べたもの。
 * WebView を PDF の描画先に描いても何も出ないことがあるため、PDF を作る前に1ページ目をビットマップに描いて確かめる。
 * 全体を調べると重いので、縦横に [SAMPLES_PER_AXIS] 点ずつ間引いて見る。
 */
internal fun isBlankImage(pixels: IntArray, width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return true
    val first = pixels[0]
    for (iy in 0 until SAMPLES_PER_AXIS) {
        val y = (height - 1) * iy / (SAMPLES_PER_AXIS - 1)
        for (ix in 0 until SAMPLES_PER_AXIS) {
            val x = (width - 1) * ix / (SAMPLES_PER_AXIS - 1)
            if (pixels[y * width + x] != first) return false
        }
    }
    return true
}

private const val SAMPLES_PER_AXIS = 24

/** 読み込みを待つ時間の上限(ミリ秒)。これを過ぎたら読み込めなかったものとして扱う */
internal const val LOAD_TIMEOUT_MILLIS = 60_000L

/** 読み込みが終わってから、あとから描かれる内容(遅れて出る画像・スクリプトの描画)を待つ時間 */
internal const val SETTLE_MILLIS = 1_500L

/** スクロールしてから、その位置が描かれるのを待つ時間 */
internal const val FRAME_WAIT_MILLIS = 250L
