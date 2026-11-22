package com.unchunks.echomark.data.podcast

import com.unchunks.components.audio.UnsupportedWavException
import com.unchunks.components.audio.WavAudio
import com.unchunks.components.audio.WavJoiner
import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.components.speechtext.SpeechNormalizer
import com.unchunks.components.speechtext.SpeechText
import com.unchunks.echomark.data.podcast.tts.PodcastSpeechEngine
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.podcast.AppReadings
import com.unchunks.echomark.domain.podcast.EpisodeLine
import com.unchunks.echomark.domain.podcast.EpisodeSource
import com.unchunks.echomark.domain.podcast.EpisodeStatus
import com.unchunks.echomark.domain.podcast.PodcastEpisode
import com.unchunks.echomark.domain.podcast.PodcastGenerationException
import com.unchunks.echomark.domain.podcast.PodcastProgress
import com.unchunks.echomark.domain.podcast.PodcastScript
import com.unchunks.echomark.domain.podcast.PodcastTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * 1つのエピソードを作る: ブックマーク → 台本([PodcastScriptWriter])→ 読みの整え([SpeechNormalizer])→ 音声([PodcastSpeechEngine])
 * → 1つの WAV([WavJoiner])。
 *
 * 読みは、利用者の辞書 → この台本で AI が見つけた読み の順に効く。行ごとに、読み上げに渡した文([EpisodeLine.spoken])と、
 * 音声の中で始まる位置([EpisodeLine.startMs])を残す。整えた結果が空になった行(URL だけの行など)は読まない。
 */
class PodcastGenerator @Inject constructor(
    private val writer: PodcastScriptWriter,
    private val engine: PodcastSpeechEngine
) {
    /**
     * @param episode 作り始めたエピソード(出典の [EpisodeSource.bookmarkId] の順に紹介する)
     * @param bookmarks 紹介するブックマーク([episode] の出典の順。消えたものは除いてある)
     * @param dictionary 利用者の読みの辞書
     * @param dir エピソードのフォルダ(音声を書き、作業のファイルは終わったら消す)
     * @throws PodcastGenerationException 作れないとき(理由は画面に出す日本語)
     */
    suspend fun generate(
        episode: PodcastEpisode,
        bookmarks: List<Bookmark>,
        dictionary: List<ReadingEntry>,
        dir: File,
        onProgress: (PodcastProgress) -> Unit
    ): PodcastEpisode {
        if (bookmarks.isEmpty()) throw PodcastGenerationException("紹介するブックマークが見つかりません(削除されたかもしれません)。")
        onProgress(PodcastProgress.Preparing)
        val sources = bookmarks.mapIndexed { index, bookmark -> PodcastTemplates.sourceOf(bookmark, index + 1, EXCERPT_CHARS) }
        val script = writer.write(sources, episode.format) { progress ->
            onProgress(
                when (progress) {
                    is ScriptProgress.Writing -> PodcastProgress.WritingScript(progress.index, progress.total)
                    is ScriptProgress.CheckingReadings -> PodcastProgress.CheckingReadings(progress.index, progress.total)
                }
            )
        }
        val spoken = script.lines.map { SpeechNormalizer.normalize(it.text, dictionary, script.readings, AppReadings.builtIn) }
        val work = File(dir, WORK_DIR).apply { deleteRecursively(); mkdirs() }
        val audio = File(dir, AUDIO_FILE)
        try {
            val starts = synthesize(script, spoken, work, audio, onProgress)
            onProgress(PodcastProgress.Saving)
            return episode.copy(
                title = script.title,
                status = EpisodeStatus.READY,
                sources = sources.map { EpisodeSource(it.bookmarkId, it.title, it.sourceName, it.url) },
                lines = script.lines.mapIndexed { index, line ->
                    EpisodeLine(line.speaker, line.text, spoken[index].plain, line.sourceIndex, starts.lines[index])
                },
                readings = usedReadings(dictionary, script.readings, spoken),
                generator = script.generator,
                audioFile = AUDIO_FILE,
                durationMs = starts.durationMs,
                sizeBytes = audio.length(),
                failureReason = null
            )
        } catch (e: Exception) {
            audio.delete()
            throw e
        } finally {
            work.deleteRecursively()
        }
    }

    private class Timeline(val lines: List<Long?>, val durationMs: Long)

    private suspend fun synthesize(
        script: PodcastScript,
        spoken: List<SpeechText>,
        work: File,
        audio: File,
        onProgress: (PodcastProgress) -> Unit
    ): Timeline {
        val starts = MutableList<Long?>(script.lines.size) { null }
        engine.open().use { session ->
            WavJoiner(audio).use { joiner ->
                script.lines.forEachIndexed { index, line ->
                    coroutineContext.ensureActive()
                    onProgress(PodcastProgress.Synthesizing(index + 1, script.lines.size))
                    val chunks = spoken[index].chunks(session.maxInputChars)
                    if (chunks.isEmpty()) return@forEachIndexed
                    // 紹介の切り替わり(出典の紹介)の前は長めに間をあける
                    val newSection = index > 0 && line.sourceIndex != script.lines[index - 1].sourceIndex
                    joiner.appendSilence(if (newSection) SECTION_PAUSE_MS else LINE_PAUSE_MS)
                    starts[index] = joiner.positionMs
                    chunks.forEachIndexed { part, chunk ->
                        val file = File(work, "line-$index-$part.wav")
                        session.synthesize(chunk, line.speaker, file)
                        val pcm = try {
                            WavAudio.read(file)
                        } catch (e: UnsupportedWavException) {
                            throw PodcastGenerationException("読み上げの音声を読めませんでした(${e.message})。", e)
                        }
                        joiner.append(pcm)
                        file.delete()
                    }
                }
                joiner.appendSilence(LINE_PAUSE_MS)
                val duration = try {
                    joiner.finish()
                } catch (e: UnsupportedWavException) {
                    throw PodcastGenerationException("読み上げる文がありませんでした。", e)
                }
                return Timeline(starts, duration)
            }
        }
    }

    /** 実際に当てた読み(利用者の辞書 → AI の順に、同じ表記は1つ)。画面で読みを確かめ・直すのに使う */
    private fun usedReadings(dictionary: List<ReadingEntry>, ai: List<ReadingEntry>, spoken: List<SpeechText>): List<ReadingEntry> {
        val used = spoken.flatMap { it.readings }.map { ReadingEntry.keyOf(it.surface) }.toSet()
        return (dictionary + ai).filter { it.matchKey in used }.distinctBy { it.matchKey }
    }

    companion object {
        const val AUDIO_FILE = "episode.wav"
        private const val WORK_DIR = "work"

        /** 台本の材料にする本文の長さ(AI に渡すときに、AI ごとの上限でさらに切る) */
        private const val EXCERPT_CHARS = 3_000
        private const val LINE_PAUSE_MS = 350L
        private const val SECTION_PAUSE_MS = 800L

        /** 失敗の理由を画面に出す文にする */
        fun reasonOf(e: Throwable): String = when (e) {
            is PodcastGenerationException -> e.message ?: "作れませんでした。"
            is CancellationException -> "途中で止まりました。もう一度作ってください。"
            else -> "作れませんでした(${e.javaClass.simpleName})。"
        }
    }
}
