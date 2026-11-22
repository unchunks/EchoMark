package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * 作り始めたエピソードを作り切る(ワーカー([com.unchunks.echomark.worker.PodcastGenerationWorker])から呼ぶ)。
 * 作れなければ、理由を付けて失敗にする。途中で消されたエピソードは、戻さない。
 */
class PodcastEpisodeRunner @Inject constructor(
    private val library: PodcastEpisodeLibrary,
    private val generator: PodcastGenerator,
    private val bookmarkRepository: BookmarkRepository,
    private val appSettings: AppSettingsRepository
) {
    /**
     * @return 作り終えたエピソード(できた・作れなかった)。消されていた・もう作り終えていたときは null
     */
    suspend fun run(id: String, onProgressTitle: (String) -> Unit = {}): PodcastEpisode? {
        val episode = library.get(id) ?: return null
        if (episode.status != EpisodeStatus.GENERATING) return null
        return try {
            val ids = episode.sources.map { it.bookmarkId }
            val byId = bookmarkRepository.getBookmarksByIds(ids).associateBy { it.id }
            val bookmarks = ids.mapNotNull { byId[it] }
            val dictionary = appSettings.readingDictionary.first()
            val made = generator.generate(episode, bookmarks, dictionary, library.dirOf(id)) { progress ->
                library.setProgress(id, progress)
                onProgressTitle(progress.label)
            }
            library.update(id) { made }
        } catch (e: CancellationException) {
            // 消された・止められた。残っていれば失敗にする(作り直せるように)
            withContext(NonCancellable) { library.update(id) { it.failed(PodcastGenerator.reasonOf(e)) } }
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ポッドキャストを作れなかった")
            library.update(id) { it.failed(PodcastGenerator.reasonOf(e)) }
        } finally {
            library.setProgress(id, null)
        }
    }

    private fun PodcastEpisode.failed(reason: String) =
        copy(status = EpisodeStatus.FAILED, failureReason = reason, audioFile = null, durationMs = null, sizeBytes = null)
}
