package com.unchunks.echomark.ui.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.unchunks.echomark.data.attachment.attachmentFile
import com.unchunks.echomark.data.video.videoFile
import com.unchunks.echomark.ui.lock.AppLockGate
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

/**
 * 端末に保存した動画(オフライン用に保存した動画・添付ファイルの動画)を、アプリの中で再生する画面(Media3 の ExoPlayer)。
 * アプリの外からは起動できない(exported=false)。
 *
 * **通信しない**: 読むのは端末のファイルだけ(FileDataSource)。HTTP で読む部品(DefaultHttpDataSource など)は使わない
 * (NetworkPolicyCoverageTest が確かめる)。開けるのは、添付ファイルと動画の置き場所の中のファイルだけ。
 */
@AndroidEntryPoint
class VideoPlayerActivity : FragmentActivity() {

    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val file = playableFile(filesDir, intent.getStringExtra(EXTRA_PATH))
        val title = intent.getStringExtra(EXTRA_TITLE)
        val exoPlayer = file?.let {
            ExoPlayer.Builder(this, ProgressiveMediaSource.Factory(FileDataSource.Factory())).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(it)))
                seekTo(savedInstanceState?.getLong(STATE_POSITION) ?: 0L)
                playWhenReady = savedInstanceState?.getBoolean(STATE_PLAYING) ?: true
                prepare()
            }
        }
        player = exoPlayer
        setContent {
            EchoMarkTheme(darkTheme = true, dynamicColor = false) {
                // 一覧から開くほか、プロセスが終わったあとに、この画面から作り直されることもある
                AppLockGate {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (exoPlayer != null) {
                            AndroidView(
                                factory = { context ->
                                    PlayerView(context).apply {
                                        this.player = exoPlayer
                                        contentDescription = title
                                    }
                                },
                                modifier = Modifier.fillMaxSize().safeDrawingPadding()
                            )
                        } else {
                            Text(
                                "動画がこの端末にありません",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { finish() },
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                            modifier = Modifier.safeDrawingPadding().padding(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 画面から離れたら止める(裏で音を出し続けない)
        player?.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putLong(STATE_POSITION, it.currentPosition)
            outState.putBoolean(STATE_PLAYING, it.playWhenReady)
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAYING = "playing"

        /** [path](filesDir からの相対パス)の動画を再生する画面の Intent */
        fun intent(context: Context, path: String, title: String?): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)

        /** 再生してよいファイル。動画の置き場所か添付ファイルの置き場所の中にあるものだけ */
        internal fun playableFile(filesDir: File, path: String?): File? =
            (videoFile(filesDir, path) ?: attachmentFile(filesDir, path))?.takeIf { it.isFile }
    }
}
