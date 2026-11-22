package com.unchunks.echomark.domain.podcast

import kotlinx.coroutines.flow.Flow

/** エピソードの状態。 */
enum class EpisodeStatus {
    /** 台本・音声を作っている(作りかけで止まったものも、次に開いたときに失敗にする) */
    GENERATING,

    /** 聞ける */
    READY,

    /** 作れなかった([PodcastEpisode.failureReason]) */
    FAILED
}

/**
 * エピソードで紹介したブックマーク。ブックマークを消しても、エピソードの出典は残す(タイトルと出典の名前・URL)。
 */
data class EpisodeSource(val bookmarkId: Long, val title: String, val sourceName: String?, val url: String?)

/**
 * エピソードの台本の1行。
 * @property spoken 読み上げに渡した文(読みの整えの結果)。画面で「読み」を確かめるのに使う
 * @property startMs 音声の中で、この行が始まる位置(ミリ秒)。まだ音声が無ければ null
 */
data class EpisodeLine(
    val speaker: PodcastSpeaker,
    val text: String,
    val spoken: String,
    val sourceIndex: Int?,
    val startMs: Long? = null
)

/**
 * ポッドキャストの1エピソード。アプリの中のファイル(`filesDir/podcasts/<id>/`)に保存し、バックアップには入れない
 * (音声は作り直せるため。台本と音声は大きくなりやすい)。DB には持たない。
 *
 * @property id フォルダの名前にも使う(英数字とハイフンだけ)
 * @property audioFile 音声のファイルの名前(フォルダの中)。できていなければ null
 * @property readings この台本で使った読み(利用者の辞書・AI の読み)。画面で読みを直すのに使う
 */
data class PodcastEpisode(
    val id: String,
    val title: String,
    val createdAt: Long,
    val format: PodcastFormat,
    val sources: List<EpisodeSource>,
    val status: EpisodeStatus,
    val lines: List<EpisodeLine> = emptyList(),
    val readings: List<ReadingEntry> = emptyList(),
    val generator: ScriptGenerator? = null,
    val audioFile: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val failureReason: String? = null
)

/** エピソードを作る進み具合(画面・通知に出す)。label は画面に出す日本語。 */
sealed interface PodcastProgress {
    val label: String

    data object Preparing : PodcastProgress {
        override val label = "準備しています"
    }

    data class WritingScript(val index: Int, val total: Int) : PodcastProgress {
        override val label get() = "台本を書いています($index/$total 件目)"
    }

    data class CheckingReadings(val index: Int, val total: Int) : PodcastProgress {
        override val label get() = "読みを確かめています($index/$total 件目)"
    }

    data class Synthesizing(val line: Int, val total: Int) : PodcastProgress {
        override val label get() = "音声にしています($line/$total 行)"
    }

    data object Saving : PodcastProgress {
        override val label = "保存しています"
    }
}

/** エピソードを作れなかった理由(画面に出す日本語)。 */
class PodcastGenerationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** ポッドキャストのエピソードを作る・一覧する・消す。 */
interface PodcastRepository {
    /** 新しい順のエピソード */
    val episodes: Flow<List<PodcastEpisode>>

    /** 作っているエピソードの進み具合(エピソードの id → 進み具合) */
    val progress: Flow<Map<String, PodcastProgress>>

    fun observeEpisode(id: String): Flow<PodcastEpisode?>

    /**
     * [bookmarkIds] から新しいエピソードを作り始める(順に紹介する。[PodcastTemplates.MAX_SOURCES] 件まで)。
     * 作るのは裏で続け、すぐに戻る。
     * @return 作り始めたエピソードの id
     */
    suspend fun create(bookmarkIds: List<Long>, format: PodcastFormat): String

    /** 消す(作っている途中なら止める)。音声のファイルも消す */
    suspend fun delete(id: String)

    /** [olderThanMillis] より前に作ったもの(作っている途中のものは除く)を消す。消した数を返す */
    suspend fun deleteOlderThan(olderThanMillis: Long): Int

    /** すべて消す */
    suspend fun deleteAll()

    /** 音声のファイル。無ければ null */
    fun audioFileOf(episode: PodcastEpisode): java.io.File?
}
