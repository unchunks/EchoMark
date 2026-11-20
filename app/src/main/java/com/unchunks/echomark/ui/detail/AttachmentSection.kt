package com.unchunks.echomark.ui.detail

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.contentKind
import com.unchunks.echomark.ui.attachment.rememberMediaDuration
import com.unchunks.echomark.ui.attachment.rememberPdfPage
import com.unchunks.echomark.ui.attachment.rememberPdfPageCount
import com.unchunks.echomark.ui.attachment.rememberVideoFrame
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.common.fileKindName
import com.unchunks.echomark.ui.common.formatDuration
import com.unchunks.echomark.ui.common.formatFileSize
import com.unchunks.echomark.ui.common.icon
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File

/** 保存したファイルの種類(MIME タイプから)。ファイルでなければ null */
internal fun Bookmark.attachmentType(): BookmarkType? =
    if (filePath == null) null else bookmarkTypeOfMimeType(mimeType) ?: type.takeIf { it != BookmarkType.URL }

/**
 * 本文の見出し。ファイルから取り出した文字(OCR・文字起こしなど)は、種類に合った名前にする
 * (本文には保存時のメモと、取り出した文字が入る)。
 */
internal fun contentLabel(bookmark: Bookmark): String = when {
    bookmark.type == BookmarkType.URL && bookmark.filePath == null -> "ページの本文・メモ"
    bookmark.type == BookmarkType.TEXT -> if (bookmark.filePath != null) "ファイルの内容・メモ" else "メモ"
    else -> when (bookmark.contentKind()) {
        ContentKind.IMAGE -> "読み取った文字・メモ"
        ContentKind.DOCUMENT -> "文書の本文・メモ"
        ContentKind.AUDIO, ContentKind.VIDEO -> "文字起こし・メモ"
        ContentKind.MEMO -> "メモ"
        ContentKind.WEB_PAGE -> "ページの本文・メモ"
    }
}

/**
 * 詳細画面の上に出すファイルのプレビュー。画像は大きく(タップで全画面・ピンチで拡大)、
 * PDF は先頭の数ページ(横にスクロール)、音声はプレーヤー、動画はコマとほかのアプリでの再生。
 */
@Composable
internal fun AttachmentPreview(
    file: File,
    type: BookmarkType,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (type) {
        BookmarkType.IMAGE -> ImagePreview(file, modifier)
        BookmarkType.PDF -> PdfPreview(file, onOpenFile, modifier)
        BookmarkType.AUDIO -> AudioPlayer(file, modifier.padding(horizontal = 16.dp))
        BookmarkType.VIDEO -> VideoPreview(file, onOpenFile, modifier.padding(horizontal = 16.dp))
        BookmarkType.TEXT, BookmarkType.URL -> Unit
    }
}

@Composable
private fun ImagePreview(file: File, modifier: Modifier) {
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    Box(modifier.padding(horizontal = 16.dp)) {
        AsyncImage(
            model = file,
            contentDescription = "保存した画像",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 440.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClickLabel = "全画面で見る") { fullScreen = true }
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Icon(Icons.Outlined.ZoomIn, contentDescription = null, modifier = Modifier.padding(4.dp).size(18.dp))
        }
    }
    if (fullScreen) ImageViewerDialog(file, onDismiss = { fullScreen = false })
}

/** 画像の全画面表示。ピンチで拡大・ドラッグで移動、ダブルタップで拡大と元の大きさを切り替える */
@Composable
private fun ImageViewerDialog(file: File, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = file,
                contentDescription = "保存した画像(拡大表示)",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            offset = if (scale == MIN_ZOOM) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > MIN_ZOOM) {
                                scale = MIN_ZOOM
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_ZOOM
                            }
                        })
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(8.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "閉じる")
            }
        }
    }
}

/** PDF の先頭の数ページを横に並べる。押すとほかのアプリ(PDF ビューア)で開く */
@Composable
private fun PdfPreview(file: File, onOpenFile: () -> Unit, modifier: Modifier) {
    val pageCount by rememberPdfPageCount(file)
    val shown = (pageCount ?: 1).coerceIn(1, MAX_PREVIEW_PAGES)
    val pageWidth = 168.dp
    val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(shown) { index ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PdfPageTile(file, index, widthPx, onOpenFile, Modifier.width(pageWidth))
                Spacer(Modifier.height(4.dp))
                Text(
                    pageCount?.let { "${index + 1} / $it" } ?: "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val rest = (pageCount ?: 0) - shown
        if (rest > 0) {
            item {
                Surface(
                    onClick = onOpenFile,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(pageWidth)
                        .aspectRatio(A4_RATIO)
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                        Spacer(Modifier.height(8.dp))
                        Text("ほか${rest}ページ", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "ほかのアプリで開く",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageTile(file: File, index: Int, widthPx: Int, onOpenFile: () -> Unit, modifier: Modifier) {
    val page by rememberPdfPage(file, index, widthPx)
    Surface(
        onClick = onOpenFile,
        shape = MaterialTheme.shapes.small,
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = modifier.semantics { contentDescription = "${index + 1}ページ目。押すとほかのアプリで開きます" }
    ) {
        val bitmap = page
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            )
        } else {
            // 描けないとき(読み込み中・壊れたファイル)は紙の形だけ見せる
            Box(Modifier.aspectRatio(A4_RATIO), contentAlignment = Alignment.Center) {
                Icon(BookmarkType.PDF.icon(), contentDescription = null, tint = Color.Gray)
            }
        }
    }
}

/** 動画: 1秒目あたりのコマと再生の印。押すとほかのアプリで再生する */
@Composable
private fun VideoPreview(file: File, onOpenFile: () -> Unit, modifier: Modifier) {
    val widthPx = with(LocalDensity.current) { 360.dp.roundToPx() }
    val frame by rememberVideoFrame(file, widthPx)
    val duration by rememberMediaDuration(file)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(Color.Black)
            .clickable(onClickLabel = "ほかのアプリで再生", onClick = onOpenFile),
        contentAlignment = Alignment.Center
    ) {
        frame?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Icon(
            Icons.Filled.PlayCircle,
            contentDescription = "動画を再生",
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
        duration?.takeIf { it > 0 }?.let {
            Text(
                formatDuration(it),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** 音声のプレーヤー(再生/一時停止・シークバー)。画面を離れる・アプリが裏に回ると止める */
@Composable
private fun AudioPlayer(file: File, modifier: Modifier) {
    val state = rememberAudioPlayerState(file)
    val knownDuration by rememberMediaDuration(file)
    AudioPlayerBar(
        isPlaying = state.isPlaying,
        positionMillis = state.positionMillis,
        durationMillis = state.durationMillis.takeIf { it > 0 } ?: knownDuration ?: 0L,
        error = state.error,
        onPlayPause = state::togglePlayPause,
        onSeek = state::seekTo,
        modifier = modifier
    )
}

/** 音声プレーヤーの見た目(状態を受け取って描くだけ) */
@Composable
internal fun AudioPlayerBar(
    isPlaying: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    error: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(52.dp), enabled = error == null) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                val duration = durationMillis.coerceAtLeast(0L)
                Slider(
                    value = if (duration > 0) positionMillis.coerceIn(0L, duration).toFloat() else 0f,
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                    enabled = duration > 0 && error == null,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "再生位置"
                            stateDescription = "${formatDuration(positionMillis)} / ${formatDuration(duration)}"
                        }
                )
            }
            Row(Modifier.padding(start = 60.dp)) {
                Text(
                    error ?: formatDuration(positionMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (durationMillis > 0) {
                    Text(formatDuration(durationMillis), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** MediaPlayer の状態。再生ボタンを初めて押したときに準備する(画面を開いただけでは読み込まない) */
private class AudioPlayerState(private val file: File) {
    var isPlaying by mutableStateOf(false)
        private set
    var positionMillis by mutableLongStateOf(0L)
        private set
    var durationMillis by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun togglePlayPause() {
        val current = player ?: prepare() ?: return
        if (current.isPlaying) {
            current.pause()
            isPlaying = false
        } else {
            current.start()
            isPlaying = true
        }
    }

    fun seekTo(millis: Long) {
        positionMillis = millis
        player?.seekTo(millis.toInt())
    }

    /** 再生中なら位置を読み直す */
    fun refreshPosition() {
        player?.takeIf { it.isPlaying }?.let { positionMillis = it.currentPosition.toLong() }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        isPlaying = false
    }

    fun release() {
        player?.release()
        player = null
        isPlaying = false
    }

    private fun prepare(): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(file.path)
            // 端末内のファイルなのですぐに終わる
            prepare()
            if (positionMillis > 0) seekTo(positionMillis.toInt())
            setOnCompletionListener {
                this@AudioPlayerState.isPlaying = false
                this@AudioPlayerState.positionMillis = 0L
                it.seekTo(0)
            }
        }.also {
            player = it
            durationMillis = it.duration.toLong().coerceAtLeast(0L)
        }
    } catch (e: Exception) {
        Timber.w(e, "音声を再生できない")
        error = "このファイルは再生できませんでした"
        null
    }
}

@Composable
private fun rememberAudioPlayerState(file: File): AudioPlayerState {
    val state = remember(file) { AudioPlayerState(file) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(state, lifecycleOwner) {
        // アプリが裏に回ったら止める(画面の外で鳴り続けない)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.release()
        }
    }
    LaunchedEffect(state, state.isPlaying) {
        while (state.isPlaying) {
            state.refreshPosition()
            delay(POSITION_REFRESH_MILLIS)
        }
    }
    return state
}

/**
 * ファイルの情報(種類のアイコン・ファイル名・種類とサイズ・ページ数や長さ)。
 * リンク先から保存したファイルは、ほかのアプリで開くボタンも出す(ファイルだけのブックマークは上の「ファイルを開く」で開く)。
 * 端末にファイルが無いとき(バックアップから読み込んだときなど)はその旨を出す。
 */
@Composable
internal fun FileInfoCard(
    bookmark: Bookmark,
    file: File?,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val type = bookmark.attachmentType()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                (type ?: bookmark.type).icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bookmark.fileName ?: "ファイル",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Text(
                    if (file == null) {
                        "ファイルはこの端末にありません(バックアップから読み込んだときなど)"
                    } else {
                        fileDetails(bookmark, file, type)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (file != null && bookmark.contentUri != null) {
                IconButton(onClick = onOpenFile) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "ファイルをほかのアプリで開く")
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** 「PDF · 2.1 MB · 12ページ」「音声 · 4.3 MB · 3:45」 */
@Composable
private fun fileDetails(bookmark: Bookmark, file: File, type: BookmarkType?): String {
    val extra = when (type) {
        BookmarkType.PDF -> rememberPdfPageCount(file).value?.let { "${it}ページ" }
        BookmarkType.AUDIO, BookmarkType.VIDEO -> rememberMediaDuration(file).value?.takeIf { it > 0 }?.let(::formatDuration)
        else -> null
    }
    return listOfNotNull(
        type?.fileKindName() ?: bookmark.type.displayName(),
        (bookmark.fileSize ?: file.length()).takeIf { it > 0 }?.let(::formatFileSize),
        extra
    ).joinToString(" · ")
}

private const val MAX_PREVIEW_PAGES = 5
private const val A4_RATIO = 1f / 1.414f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val POSITION_REFRESH_MILLIS = 250L
