package com.unchunks.echomark.data.podcast.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.data.podcast.PodcastEpisodeLibrary
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastPlayback
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.ui.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ポッドキャストを再生するサービス(Media3 の [MediaSessionService])。画面を離れても再生を続け、メディアの通知・ロック画面・
 * イヤホンのボタンから操作できる。
 *
 * - **通信しない**: 読むのはエピソードの置き場所(`filesDir/podcasts/`)の音声だけ([FileDataSource])。再生するものは
 *   エピソードの ID で受け取り、このサービスがファイルを決める([onAddMediaItems])。ほかのファイル・URL は再生しない
 * - **つなげる相手を限る**: このアプリ・メディアの通知・端末(ロック画面・Bluetooth など、プラットフォームのセッションを通すもの)・
 *   車の画面だけ。ほかのアプリからは、題名(ブックマークのタイトル)を読めず、操作もできない
 * - **アプリのロック中は題名を出さない**: 通知・ロック画面には「EchoMark ポッドキャスト」とだけ出す(ウィジェットと同じ考え方)
 * - 話し声なので、ほかのアプリが音を出すと止まり(オーディオフォーカス)、イヤホンを抜くと止まる
 */
@AndroidEntryPoint
class PodcastPlaybackService : MediaSessionService() {

    @Inject lateinit var library: PodcastEpisodeLibrary
    @Inject lateinit var appSettings: AppSettingsRepository
    @Inject lateinit var dispatcherProvider: DispatcherProvider

    private var session: MediaSession? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcherProvider.main) }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, ProgressiveMediaSource.Factory(FileDataSource.Factory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_BACK_MS)
            .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                session?.setSessionActivity(sessionActivity(mediaItem?.mediaId))
            }
        })
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_podcast_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) }
        )
        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity(null))
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 止めているときに最近使ったアプリから消されたら、サービスも終える(再生中なら続ける) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** 通知をタップしたら、そのエピソードの再生の画面を開く */
    private fun sessionActivity(episodeId: String?): PendingIntent {
        val uri = (episodeId?.let { Routes.podcastPlayerDeepLink(it) } ?: Routes.PODCASTS_DEEP_LINK).toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri, this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, SESSION_ACTIVITY_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @OptIn(UnstableApi::class)
    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (isTrusted(session, controller)) {
                super.onConnect(session, controller)
            } else {
                Timber.i("ポッドキャストの再生に、ほかのアプリからつなごうとした: %s", controller.packageName)
                MediaSession.ConnectionResult.reject()
            }

        /** エピソードの ID から、再生するファイルを決める。できていないエピソード・ファイルの無いものは再生しない */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val result = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                try {
                    val hideTitle = appSettings.appLock.first().let { it.enabled || it.hideContent }
                    result.set(mediaItems.mapNotNull { resolve(it.mediaId, hideTitle) }.toMutableList())
                } catch (e: Exception) {
                    Timber.w(e, "ポッドキャストの音声を読み込めなかった")
                    result.setException(e)
                }
            }
            return result
        }
    }

    @OptIn(UnstableApi::class)
    private fun isTrusted(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == packageName ||
            session.isMediaNotificationController(controller) ||
            session.isAutoCompanionController(controller) ||
            session.isAutomotiveController(controller) ||
            // ロック画面・Bluetooth・端末の音量ボタンなど(プラットフォームのセッションを通すもの)
            controller.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME

    private suspend fun resolve(episodeId: String, hideTitle: Boolean): MediaItem? {
        val episode = library.get(episodeId)?.takeIf { it.status == EpisodeStatus.READY } ?: return null
        val file = library.audioFileOf(episode) ?: return null
        val metadata = MediaMetadata.Builder()
            .setTitle(if (hideTitle) getString(R.string.podcast_playback_title_hidden) else episode.title)
            .setArtist(getString(R.string.podcast_playback_artist))
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val SESSION_ID = "podcast"
        const val SKIP_BACK_MS = PodcastPlayback.SKIP_BACK_MS
        const val SKIP_FORWARD_MS = PodcastPlayback.SKIP_FORWARD_MS
        private const val SESSION_ACTIVITY_REQUEST = 3103

    }
}
