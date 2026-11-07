package com.unchunks.echomark.domain.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 録音を続けるかの判定。100 ミリ秒ごとの音の大きさを渡す。 */
class SpeechEndDetectorTest {

    private val quiet = 100.0
    private val voice = 2_000.0

    private fun SpeechEndDetector.feed(level: Double, frames: Int): Boolean {
        var ended = false
        repeat(frames) { if (accept(level, FRAME_MILLIS)) ended = true }
        return ended
    }

    @Test
    fun 話した後に静かな時間が続くと終わる() {
        val detector = SpeechEndDetector()
        detector.feed(quiet, 3)
        assertFalse(detector.feed(voice, 10))
        assertTrue(detector.heardSpeech)

        assertFalse(detector.feed(quiet, 14))
        assertTrue(detector.feed(quiet, 1))
    }

    @Test
    fun 話の途中の短い間では終わらない() {
        val detector = SpeechEndDetector()
        detector.feed(quiet, 3)

        repeat(5) {
            assertFalse(detector.feed(voice, 5))
            assertFalse(detector.feed(quiet, 10))
        }
    }

    @Test
    fun 話し始めないまま時間が過ぎたら諦める() {
        val detector = SpeechEndDetector()

        assertFalse(detector.feed(quiet, 79))
        assertTrue(detector.feed(quiet, 1))
        assertFalse(detector.heardSpeech)
    }

    @Test
    fun 周りがうるさいときは雑音を話し声にしない() {
        val detector = SpeechEndDetector()

        detector.feed(1_000.0, 20)

        assertFalse(detector.heardSpeech)
        detector.feed(3_500.0, 1)
        assertTrue(detector.heardSpeech)
    }

    @Test
    fun 話し続けても長さの上限で終わる() {
        val detector = SpeechEndDetector(maxMillis = 1_000)
        detector.feed(quiet, 2)

        assertTrue(detector.feed(voice, 8))
    }

    private companion object {
        const val FRAME_MILLIS = 100L
    }
}
