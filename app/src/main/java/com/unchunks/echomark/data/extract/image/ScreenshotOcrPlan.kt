package com.unchunks.echomark.data.extract.image

import kotlin.math.max
import kotlin.math.min

/*
 * スクリーンショットの OCR の組み立て。Android に依存しない(単体テストでも使う)。
 *
 * 長いスクリーンショット(スクロール撮影など)をそのまま OCR すると、長辺 2048px に縮められて文字が小さくなりすぎ、
 * 読み取れなくなる。長い辺に沿って、元の大きさのまま少し重ねた帯に分けて読み、つなぎ合わせる。
 */

/** 画像の中の範囲([start] 以上 [end] 未満。長い辺に沿った位置) */
internal data class Band(val start: Int, val end: Int)

/**
 * 長い画像を、元の大きさのまま OCR できる帯に分ける。分けなくてよい(縮めても読める)ときは空のリスト。
 *
 * - 長辺が [maxSide] の [SPLIT_LONG_SIDE_FACTOR] 倍以下(縮めても約 2/3 の大きさで、普通の画面の1枚は読める)、または縦横比が [MIN_ELONGATED_RATIO] 未満なら分けない
 * - 帯の長さは短い辺の [BAND_LENGTH_RATIO] 倍(縮小されない長さ)で、前の帯と [OVERLAP_RATIO] だけ重ねる(行が帯の境目で切れても読めるように)
 * - [maxBands] を超えるほど長いときは分けない(普通に縮めて読む。時間とメモリを使いすぎないため)
 */
internal fun planBands(
    width: Int,
    height: Int,
    maxSide: Int = DEFAULT_MAX_SIDE,
    maxBands: Int = MAX_BANDS
): List<Band> {
    if (width <= 0 || height <= 0) return emptyList()
    val longSide = max(width, height)
    val shortSide = min(width, height)
    if (longSide <= maxSide * SPLIT_LONG_SIDE_FACTOR || longSide.toDouble() / shortSide < MIN_ELONGATED_RATIO) return emptyList()

    val length = min((shortSide * BAND_LENGTH_RATIO).toInt(), maxSide)
    val step = max(1, (length * (1 - OVERLAP_RATIO)).toInt())
    val bands = mutableListOf<Band>()
    var start = 0
    while (true) {
        val end = min(start + length, longSide)
        bands += Band(start, end)
        if (end >= longSide) break
        start += step
        if (bands.size > maxBands) return emptyList()
    }
    // 最後の帯が前の帯にほとんど含まれるときは、前の帯に含めて数を減らす
    if (bands.size >= 2 && bands.last().end - bands.last().start < length * MIN_LAST_BAND_RATIO) {
        val last = bands.removeAt(bands.lastIndex)
        bands[bands.lastIndex] = bands.last().copy(end = last.end)
    }
    return if (bands.size > maxBands) emptyList() else bands
}

/**
 * 帯ごとの OCR の結果をつなぐ。帯の重なりで同じ行が二重に読まれるので、前の結果の末尾と次の結果の先頭が
 * 同じ並びの行を、次の側から取り除く(空行は無視して比べる)。
 */
internal fun mergeBandTexts(texts: List<String>): String {
    val merged = mutableListOf<String>()
    for (text in texts) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        val overlap = overlapLength(merged, lines)
        merged += lines.drop(overlap)
    }
    return merged.joinToString("\n")
}

/** [previous] の末尾と [next] の先頭が一致する最大の行数 */
private fun overlapLength(previous: List<String>, next: List<String>): Int {
    val limit = min(min(previous.size, next.size), MAX_OVERLAP_LINES)
    for (count in limit downTo 1) {
        if (previous.subList(previous.size - count, previous.size) == next.subList(0, count)) return count
    }
    return 0
}

/**
 * スクリーンショットらしい画像か。カメラの情報(EXIF の機種)が無い PNG で、縦横比が [MIN_SCREENSHOT_RATIO] 以上
 * (スマホの画面は 16:9 〜 21:9、スクロール撮影はもっと細長い)。推定なので、外れても保存や検索に支障が無い使い方(種類の書き添え)に限る。
 */
internal fun looksLikeScreenshot(extension: String?, width: Int, height: Int, hasCameraInfo: Boolean): Boolean {
    if (hasCameraInfo || extension?.lowercase() != "png") return false
    if (width <= 0 || height <= 0) return false
    return max(width, height).toDouble() / min(width, height) >= MIN_SCREENSHOT_RATIO
}

/** 本文の先頭に書き添える、画像の種類の行 */
internal const val SCREENSHOT_LABEL = "種類: スクリーンショット"

private const val DEFAULT_MAX_SIDE = 2048
private const val MAX_BANDS = 12
private const val SPLIT_LONG_SIDE_FACTOR = 1.5
private const val MIN_ELONGATED_RATIO = 2.2
private const val BAND_LENGTH_RATIO = 1.8
private const val OVERLAP_RATIO = 0.1
private const val MIN_LAST_BAND_RATIO = 0.25
private const val MAX_OVERLAP_LINES = 12
private const val MIN_SCREENSHOT_RATIO = 1.7
