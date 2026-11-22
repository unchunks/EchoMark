package com.unchunks.echomark.data.podcast.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastPlayback
import com.unchunks.echomark.domain.podcast.PodcastPlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [PodcastPlaybackService] に [MediaController] でつないで再生する。つなぐのは最初に再生するときで、つないだら使い回す
 * (サービスが終わって切れたら、次の再生でつなぎ直す)。再生中は位置を 0.25 秒ごとに知らせる。
 * MediaController はメインスレッドで使う。
 */
@Singleton
class MediaControllerPodcastPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) : PodcastPlayback {

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.main)
    private val mutableState = MutableStateFlow(PodcastPlaybackState())
    override val state: StateFlow<PodcastPlaybackState> = mutableState.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (player.isPlaying) startTicker() else stopTicker()
        }
    }

    override suspend fun play(episode: PodcastEpisode, startMs: Long?): Boolean = withContext(dispatcherProvider.main) {
        if (episode.status != EpisodeStatus.READY) return@withContext false
        val player = connect() ?: return@withContext false
        if (player.currentMediaItem?.mediaId == episode.id && player.playbackState != Player.STATE_IDLE) {
            startMs?.let(player::seekTo)
            if (player.playbackState == Player.STATE_ENDED && startMs == null) player.seekTo(0)
        } else {
            // 再生するファイルは、サービスがエピソードの ID から決める(PodcastPlaybackService.onAddMediaItems)
            player.setMediaItem(MediaItem.Builder().setMediaId(episode.id).build(), startMs ?: 0L)
            player.prepare()
        }
        player.play()
        refresh()
        true
    }

    override fun pause() {
        controller?.pause()
    }

    override fun resume() {
        controller?.let {
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            it.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        controller?.let {
            it.seekTo(positionMs.coerceAtLeast(0))
            refresh()
        }
    }

    override fun seekBy(deltaMs: Long) {
        controller?.let { player ->
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
            refresh()
        }
    }

    override fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    override fun stopIfPlaying(episodeId: String) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == episodeId) {
            player.stop()
            player.clearMediaItems()
            refresh()
        }
    }

    private suspend fun connect(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MediaControllerPodcastPlayback.controller === controller) {
                        this@MediaControllerPodcastPlayback.controller = null
                        stopTicker()
                        mutableState.value = PodcastPlaybackState(speed = mutableState.value.speed)
                    }
                }
            })
            .buildAsync()
        val connected = suspendCancellableCoroutine<MediaController?> { continuation ->
            future.addListener(
                { continuation.resume(runCatching { future.get() }.onFailure { Timber.w(it, "ポッドキャストの再生につなげない") }.getOrNull()) },
                ContextCompat.getMainExecutor(context)
            )
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        } ?: return null
        connected.addListener(listener)
        // 前に選んだ速さを、つなぎ直したときも使う
        connected.setPlaybackSpeed(mutableState.value.speed)
        controller = connected
        return connected
    }

    private fun refresh() {
        val player = controller ?: return
        mutableState.value = PodcastPlaybackState(
            episodeId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            speed = player.playbackParameters.speed
        )
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                refresh()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
