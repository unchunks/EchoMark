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
