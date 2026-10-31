package com.unchunks.echomark.domain.chat

/**
 * 録音を続けるかを、音の大きさから決める。Android に依存しない純粋な処理で、単体テストできる。
 *
 * 部屋の静かさは場所によって違うため、これまでで最も小さかった音を基準にし、その数倍の音を話し声とみなす。
 * 話し始めた後に静かな時間が続いたら話し終わったとする。話し始めないまま時間が過ぎたら諦める。
 * 判定が外れても、録音の画面の停止ボタンと [maxMillis] で必ず終わる。
 */
class SpeechEndDetector(
    private val silenceMillis: Long = DEFAULT_SILENCE_MILLIS,
    private val noSpeechMillis: Long = DEFAULT_NO_SPEECH_MILLIS,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS
) {
    private var elapsedMillis = 0L
    private var quietMillis = 0L
    private var noiseFloor = Double.MAX_VALUE

    /** 話し声を聞いたか。 */
    var heardSpeech = false
        private set

    /**
     * 録音した区間を1つ受け取る。
     * @param level その区間の音の大きさ(16bit PCM の二乗平均平方根)
     * @return 録音を終えるなら true
     */
    fun accept(level: Double, durationMillis: Long): Boolean {
        elapsedMillis += durationMillis
        noiseFloor = minOf(noiseFloor, level)
        val threshold = maxOf(MIN_SPEECH_LEVEL, noiseFloor * SPEECH_TO_NOISE_RATIO)
        if (level >= threshold) {
            heardSpeech = true
            quietMillis = 0
        } else {
            quietMillis += durationMillis
        }
        return when {
            elapsedMillis >= maxMillis -> true
            heardSpeech -> quietMillis >= silenceMillis
            else -> elapsedMillis >= noSpeechMillis
        }
    }

    companion object {
        const val DEFAULT_SILENCE_MILLIS = 1_500L
        const val DEFAULT_NO_SPEECH_MILLIS = 8_000L

        /** 1回の質問の録音の長さの上限 */
        const val DEFAULT_MAX_MILLIS = 60_000L

        /** 話し声とみなす最小の大きさ(無音に近い基準でも、小さな雑音を話し声にしない) */
        private const val MIN_SPEECH_LEVEL = 300.0

        /** 基準の音のこの倍以上を話し声とみなす */
        private const val SPEECH_TO_NOISE_RATIO = 3.0
    }
}
