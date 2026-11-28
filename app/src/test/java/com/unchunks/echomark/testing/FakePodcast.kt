package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File

/** エピソードをメモリに持つ [PodcastRepository]。作ったときの引数を覚えておく */
class FakePodcastRepository : PodcastRepository {
    val episodesFlow = MutableStateFlow<List<PodcastEpisode>>(emptyList())
    val progressFlow = MutableStateFlow<Map<String, PodcastProgress>>(emptyMap())
    val created = mutableListOf<Pair<List<Long>, PodcastFormat>>()
    var createFailure: Exception? = null
    var olderThanCalls = mutableListOf<Long>()

    override val episodes: Flow<List<PodcastEpisode>> = episodesFlow
    override val progress: Flow<Map<String, PodcastProgress>> = progressFlow

    override fun observeEpisode(id: String): Flow<PodcastEpisode?> = episodesFlow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun create(bookmarkIds: List<Long>, format: PodcastFormat): String {
        createFailure?.let { throw it }
        created += bookmarkIds to format
        val id = "ep${created.size}"
        episodesFlow.update {
            listOf(PodcastEpisode(id, "新しい", 0, format, bookmarkIds.map { EpisodeSource(it, "t$it", null, null) }, EpisodeStatus.GENERATING)) + it
        }
        return id
    }

    override suspend fun delete(id: String) {
        episodesFlow.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun deleteOlderThan(olderThanMillis: Long): Int {
        olderThanCalls += olderThanMillis
        val old = episodesFlow.value.filter { it.createdAt < olderThanMillis && it.status != EpisodeStatus.GENERATING }
        episodesFlow.update { it - old.toSet() }
        return old.size
    }

    override suspend fun deleteAll() {
        episodesFlow.value = emptyList()
    }

    override fun audioFileOf(episode: PodcastEpisode): File? = null
}

/** 再生の Fake。呼ばれた操作を覚え、状態を書き換える */
class FakePodcastPlayback : com.unchunks.echomark.domain.podcast.PodcastPlayback {
    val stateFlow = MutableStateFlow(com.unchunks.echomark.domain.podcast.PodcastPlaybackState())
    override val state: kotlinx.coroutines.flow.StateFlow<com.unchunks.echomark.domain.podcast.PodcastPlaybackState> = stateFlow
    val calls = mutableListOf<String>()
    var playResult = true

    override suspend fun play(episode: PodcastEpisode, startMs: Long?): Boolean {
        calls += "play:${episode.id}:$startMs"
        if (playResult) stateFlow.value = stateFlow.value.copy(episodeId = episode.id, isPlaying = true, positionMs = startMs ?: 0, durationMs = episode.durationMs ?: 0)
        return playResult
    }

    override fun pause() {
        calls += "pause"
        stateFlow.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        calls += "resume"
        stateFlow.update { it.copy(isPlaying = true) }
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
        stateFlow.update { it.copy(positionMs = positionMs) }
    }

    override fun seekBy(deltaMs: Long) {
        calls += "seekBy:$deltaMs"
        stateFlow.update { it.copy(positionMs = (it.positionMs + deltaMs).coerceAtLeast(0)) }
    }

    override fun setSpeed(speed: Float) {
        calls += "speed:$speed"
        stateFlow.update { it.copy(speed = speed) }
    }

    override fun stopIfPlaying(episodeId: String) {
        calls += "stop:$episodeId"
        if (stateFlow.value.episodeId == episodeId) stateFlow.value = com.unchunks.echomark.domain.podcast.PodcastPlaybackState(speed = stateFlow.value.speed)
    }
}
