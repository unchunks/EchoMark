package com.unchunks.echomark.domain.repository

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/** 音声での質問の結果。 */
sealed interface VoiceInputResult {
    /** 聞き取れた([text] は空でない) */
    data class Text(val text: String) : VoiceInputResult

    /** 話した内容を聞き取れなかった */
    data object NoSpeech : VoiceInputResult

    /** マイクを使えなかった(ほかのアプリが使っている・許可が無いなど) */
    data object MicrophoneUnavailable : VoiceInputResult

    /** 文字起こしの仕組みを使えなかった(端末が対応していない・言語のモデルが無いなど) */
    data object TranscriptionUnavailable : VoiceInputResult
}

/** マイクの音声を文字にする。 */
interface ChatVoiceInput {
    /**
     * マイクから録音し、話し終わった(無音が続いた・長さの上限・[finish] の完了)ら文字に起こす。
     * コルーチンをキャンセルすると、録音を捨てて終わる。録音はこの呼び出しの間だけ行い、音声は残さない。
     * マイクの権限は呼び出し側で確かめる。
     * @param onTranscribing 録音を終えて文字起こしを始めるときに呼ばれる
     */
    suspend fun listen(finish: Deferred<Unit>, onTranscribing: () -> Unit): VoiceInputResult
}

/** 回答の読み上げ。 */
interface ChatSpeechOutput {
    /** 読み上げている回答の messageId。読み上げていなければ null。 */
    val speakingMessageId: StateFlow<Long?>

    /**
     * [parts] を順に読み上げる。読み上げ中のものは止める。
     * @return 読み上げを始められなければ false(端末に読み上げの仕組み・日本語の音声が無い)
     */
    suspend fun speak(messageId: Long, parts: List<String>): Boolean

    fun stop()
}
