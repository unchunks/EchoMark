package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastRepository
import com.unchunks.echomark.domain.podcast.PodcastTemplates
import com.unchunks.echomark.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 作るのを裏で続ける仕組み(WorkManager)。テストで差し替える。 */
interface PodcastWorkScheduler {
    fun enqueue(episodeId: String)
    fun cancel(episodeId: String)
}

@Singleton
class PodcastRepositoryImpl @Inject constructor(
    private val library: PodcastEpisodeLibrary,
    private val scheduler: PodcastWorkScheduler,
    private val bookmarkRepository: BookmarkRepository
) : PodcastRepository {

    /** テストで時刻と id を決めるため */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var newId: () -> String = { UUID.randomUUID().toString() }

    override val episodes: Flow<List<PodcastEpisode>> = library.episodes

    override val progress: Flow<Map<String, PodcastProgress>> = library.progress

    override fun observeEpisode(id: String): Flow<PodcastEpisode?> =
        library.episodes.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    override suspend fun create(bookmarkIds: List<Long>, format: PodcastFormat): String {
        val ids = bookmarkIds.distinct().take(PodcastTemplates.MAX_SOURCES)
        if (ids.isEmpty()) throw PodcastGenerationException("ブックマークを選んでください。")
        val byId = bookmarkRepository.getBookmarksByIds(ids).associateBy { it.id }
        val bookmarks = ids.mapNotNull { byId[it] }
        if (bookmarks.isEmpty()) throw PodcastGenerationException("選んだブックマークが見つかりません。")
        val sources = bookmarks.mapIndexed { index, bookmark -> PodcastTemplates.sourceOf(bookmark, index + 1, excerptChars = 0) }
        val episode = PodcastEpisode(
            id = newId(),
            title = PodcastTemplates.episodeTitle(sources),
            createdAt = clock(),
            format = format,
            sources = sources.map { EpisodeSource(it.bookmarkId, it.title, it.sourceName, it.url) },
            status = EpisodeStatus.GENERATING
        )
        library.put(episode)
        scheduler.enqueue(episode.id)
        return episode.id
    }

    override suspend fun delete(id: String) {
        scheduler.cancel(id)
        library.remove(id)
    }

    override suspend fun deleteOlderThan(olderThanMillis: Long): Int {
        val old = library.episodes.firstList().filter { it.createdAt < olderThanMillis && it.status != EpisodeStatus.GENERATING }
        old.forEach { library.remove(it.id) }
        return old.size
    }

    override suspend fun deleteAll() {
        library.episodes.firstList().filter { it.status == EpisodeStatus.GENERATING }.forEach { scheduler.cancel(it.id) }
        library.removeAll()
    }

    override fun audioFileOf(episode: PodcastEpisode): File? = library.audioFileOf(episode)

    private suspend fun Flow<List<PodcastEpisode>>.firstList(): List<PodcastEpisode> = first()
}
