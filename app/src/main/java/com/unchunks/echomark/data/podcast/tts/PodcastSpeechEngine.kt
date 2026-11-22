package com.unchunks.echomark.data.podcast.tts

import com.unchunks.echomark.domain.podcast.PodcastSpeaker
import com.unchunks.echomark.domain.podcast.SpeechText
import java.io.File

/**
 * 台本の文を音声のファイルにする仕組み。端末の読み上げ([AndroidPodcastSpeechEngine])と、テストの Fake を差し替える。
 */
interface PodcastSpeechEngine {
    /**
     * 音声にする準備をする(エンジンの起動・声の選択)。使い終わったら閉じる。
     * @throws com.unchunks.echomark.domain.podcast.PodcastGenerationException 使えない(読み上げのアプリが無い・日本語の端末内の声が無い)とき
     */
    suspend fun open(): PodcastSpeechSession
}

interface PodcastSpeechSession : AutoCloseable {
    /** 1回に渡せる文字数 */
    val maxInputChars: Int

    /** 2人の掛け合いで、2人に違う声を使えるか(使えなければ、同じ声の高さを変える) */
    val hasDistinctVoices: Boolean

    /** [text] を [speaker] の声で、WAV のファイル [output] にする */
    suspend fun synthesize(text: SpeechText, speaker: PodcastSpeaker, output: File)
}
