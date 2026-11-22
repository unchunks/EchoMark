package com.unchunks.echomark.domain.podcast

import kotlinx.coroutines.flow.StateFlow

/**
 * 再生の状態。
 * @property episodeId 読み込んでいるエピソード。何も読み込んでいなければ null
 * @property durationMs 長さ。分からなければ 0
 */
data class PodcastPlaybackState(
    val episodeId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f
)

/**
 * ポッドキャストの再生。アプリの中の再生(バックグラウンドでも続き、通知・ロック画面から操作できる)と、テストの Fake を差し替える。
 */
interface PodcastPlayback {
    val state: StateFlow<PodcastPlaybackState>

    /**
     * [episode] を [startMs] から再生する。同じエピソードを読み込んでいれば、[startMs] が null なら今の位置から続ける。
     * @return 再生を始められたか(音声のファイルが無い・再生の仕組みにつながらないときは false)
     */
    suspend fun play(episode: PodcastEpisode, startMs: Long? = null): Boolean

    fun pause()

    fun resume()

    fun seekTo(positionMs: Long)

    /** 今の位置から [deltaMs] だけ進める(負なら戻す) */
    fun seekBy(deltaMs: Long)

    fun setSpeed(speed: Float)

    /** [episodeId] を読み込んでいれば止めて外す(エピソードを消すとき) */
    fun stopIfPlaying(episodeId: String)

    companion object {
        /** 選べる速さ */
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        /** 戻る・進むの幅 */
        const val SKIP_BACK_MS = 10_000L
        const val SKIP_FORWARD_MS = 30_000L
    }
}

/** 台本と音声の位置の対応(純粋関数)。 */
object EpisodeTimeline {
    /** [positionMs] で読んでいる行の番号。まだ最初の行の前・位置の無い台本なら null */
    fun currentLineIndex(lines: List<EpisodeLine>, positionMs: Long): Int? =
        lines.indices.lastOrNull { index -> lines[index].startMs?.let { it <= positionMs } == true }

    /** [sourceIndex] 件目(1 始まり)の紹介が始まる位置(出典の紹介の行)。無ければ null */
    fun sourceStartMs(lines: List<EpisodeLine>, sourceIndex: Int): Long? =
        lines.firstOrNull { it.sourceIndex == sourceIndex && it.startMs != null }?.startMs

    /** 「1:05」「1:02:03」の形の時間 */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1_000)
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
