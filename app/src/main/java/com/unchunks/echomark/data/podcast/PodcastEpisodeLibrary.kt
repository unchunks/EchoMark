package com.unchunks.echomark.data.podcast

import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 保存したエピソード([PodcastEpisodeStore])を、画面で見られる形で持つ。書き込みは1つずつ順に行い、書いたら一覧を流し直す。
 * 作っている途中の進み具合([progress])はメモリの中だけに持つ(作っているワーカーと同じプロセスで動く)。
 */
class PodcastEpisodeLibrary(
    private val store: PodcastEpisodeStore,
    private val io: CoroutineDispatcher
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<PodcastEpisode>?>(null)
    private val progressState = MutableStateFlow<Map<String, PodcastProgress>>(emptyMap())

    /** 新しい順のエピソード。最初に集めたときにファイルから読む */
    val episodes: Flow<List<PodcastEpisode>> = state.onStart { ensureLoaded() }.filterNotNull()

    val progress: StateFlow<Map<String, PodcastProgress>> = progressState.asStateFlow()

    suspend fun get(id: String): PodcastEpisode? = withLock { store.read(id) }

    suspend fun put(episode: PodcastEpisode) = withLock { store.write(episode) }

    /** あるときだけ書き換える(消したエピソードを、作り終えたワーカーが戻さないように)。書き換えたものを返す */
    suspend fun update(id: String, transform: (PodcastEpisode) -> PodcastEpisode): PodcastEpisode? = withLock {
        store.read(id)?.let(transform)?.also(store::write)
    }

    suspend fun remove(id: String): Boolean = withLock { store.delete(id) }.also { setProgress(id, null) }

    suspend fun removeAll() = withLock { store.deleteAll() }.also { progressState.value = emptyMap() }

    /** [id] のエピソードのフォルダ(音声と作業のファイルを置く) */
    fun dirOf(id: String): File = store.dirOf(id)

    fun audioFileOf(episode: PodcastEpisode): File? = store.audioFileOf(episode)

    fun setProgress(id: String, progress: PodcastProgress?) {
        progressState.update { if (progress == null) it - id else it + (id to progress) }
    }

    private suspend fun ensureLoaded() {
        if (state.value == null) withLock { }
    }

    /** 書き込みのあと(と最初の読み込み)に、一覧を読み直す */
    private suspend fun <T> withLock(block: () -> T): T = withContext(io) {
        mutex.withLock {
            val result = block()
            state.value = store.list()
            result
        }
    }
}
