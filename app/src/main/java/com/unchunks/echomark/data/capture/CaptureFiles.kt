package com.unchunks.echomark.data.capture

import com.unchunks.echomark.domain.capture.CaptureKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * アプリの中で撮った写真・動画・録音の一時ファイルの名前。Android に依存しない(単体テストでも使う)。
 */

/** 一時ファイルを置く cacheDir 内のディレクトリ名(file_paths.xml の cache-path と合わせる) */
internal const val CAPTURE_DIRECTORY = "captures"

private val NAME_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

/**
 * 一時ファイルの名前。「写真 2026-10-31 12-34-56.jpg」のように撮った日時を入れる(保存したときのタイトルになる)。
 * 同じ秒に重なったときは [sequence] で区別する。
 */
internal fun captureFileName(kind: CaptureKind, nowMillis: Long, zone: ZoneId, sequence: Int = 0): String {
    val time = NAME_TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis).atZone(zone))
    val suffix = if (sequence > 0) " ($sequence)" else ""
    return "${kind.label} $time$suffix.${kind.extension}"
}

/** FileProvider の URI の最後の部分(ファイル名)が、一時ファイルとして受け付ける形か(ディレクトリの区切りや「..」を含めない) */
internal fun isSafeCaptureName(name: String?): Boolean =
    !name.isNullOrEmpty() && name.length <= MAX_CAPTURE_NAME_LENGTH && name != ".." && name != "." &&
        name.none { it == '/' || it == '\\' || it.isISOControl() }

private const val MAX_CAPTURE_NAME_LENGTH = 100
