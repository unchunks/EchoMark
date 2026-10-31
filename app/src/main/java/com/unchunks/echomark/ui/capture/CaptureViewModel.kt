package com.unchunks.echomark.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.capture.CaptureFiles
import com.unchunks.echomark.domain.capture.CaptureKind
import com.unchunks.echomark.domain.capture.CaptureTarget
import com.unchunks.echomark.domain.capture.VoiceMemoRecorder
import com.unchunks.echomark.domain.capture.VoiceMemoStop
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 音声メモの録音の状態 */
sealed interface VoiceMemoState {
    data object Idle : VoiceMemoState

    /** 録音中。[level] は音の大きさ(0〜1) */
    data class Recording(val elapsedMillis: Long, val level: Float) : VoiceMemoState
}

/** 撮った・録ったものの結果(追加シートが受け取る) */
sealed interface CaptureEvent {
    /** 追加できる一時ファイルができた */
    data class Captured(val uri: String, val mimeType: String) : CaptureEvent

    /** 利用者に知らせる(撮れなかった・録れなかった理由) */
    data class Message(val text: String) : CaptureEvent
}

/**
 * 追加シートでの写真・動画の撮影と音声メモの録音。
 * 写真・動画は端末のカメラのアプリに任せ(このアプリはカメラの権限を持たない)、撮れたファイルを追加するだけ。
 * 音声メモはマイクの許可が得られてから [startVoiceMemo] で始める(許可は録音のボタンを押したときにだけ求める)。
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val files: CaptureFiles,
    private val recorder: VoiceMemoRecorder,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val voiceMemoFlow = MutableStateFlow<VoiceMemoState>(VoiceMemoState.Idle)
    val voiceMemo: StateFlow<VoiceMemoState> = voiceMemoFlow.asStateFlow()

    private val eventChannel = Channel<CaptureEvent>(Channel.BUFFERED)
    val events: Flow<CaptureEvent> = eventChannel.receiveAsFlow()

    private var memoTarget: CaptureTarget? = null
    private var memoJob: Job? = null

    init {
        // 前に撮ったまま保存しなかった一時ファイルを掃除する
        viewModelScope.launch { withContext(dispatchers.io) { files.purgeStale(STALE_MILLIS) } }
    }

    /** カメラのアプリに渡す、撮影の出力先を用意する。用意できなければ null(理由は [events] で知らせる) */
    fun prepareCamera(kind: CaptureKind): CaptureTarget? {
        require(kind != CaptureKind.VOICE_MEMO)
        return files.create(kind) ?: run {
            eventChannel.trySend(CaptureEvent.Message("撮影の準備ができませんでした。空き容量を確かめてください"))
            null
        }
    }

    /** カメラのアプリから戻った。撮れていれば追加し、取り消されていれば一時ファイルを捨てる */
    fun onCameraResult(target: CaptureTarget, kind: CaptureKind, captured: Boolean) {
        if (captured && files.hasContent(target.uri)) {
            eventChannel.trySend(CaptureEvent.Captured(target.uri, kind.mimeType))
        } else {
            files.discard(listOf(target.uri))
        }
    }

    /** 追加シートから外した・閉じたときに、撮った・録った一時ファイルを捨てる */
    fun discard(uris: Collection<String>) = files.discard(uris)

    /** 音声メモの録音を始める(マイクの許可を確かめてから呼ぶ) */
    fun startVoiceMemo() {
        if (voiceMemoFlow.value is VoiceMemoState.Recording) return
        val target = files.create(CaptureKind.VOICE_MEMO)
        if (target == null) {
            eventChannel.trySend(CaptureEvent.Message("録音の準備ができませんでした。空き容量を確かめてください"))
            return
        }
        // 録音が途中で止まったときは、呼ばれたスレッドから状態を触らず viewModelScope で処理する
        val started = recorder.start(target) { viewModelScope.launch { failVoiceMemo("録音が止まりました。マイクをほかのアプリが使っていないか確かめてください") } }
        if (!started) {
            files.discard(listOf(target.uri))
            eventChannel.trySend(CaptureEvent.Message("マイクを使えませんでした。ほかのアプリが使っていないか確かめてください"))
            return
        }
        memoTarget = target
        voiceMemoFlow.value = VoiceMemoState.Recording(elapsedMillis = 0L, level = 0f)
        memoJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                val elapsed = recorder.elapsedMillis()
                if (elapsed >= MAX_MEMO_MILLIS) {
                    stopVoiceMemo()
                    return@launch
                }
                voiceMemoFlow.value = VoiceMemoState.Recording(elapsed, recorder.level())
            }
        }
    }

    /** 録音を終えて、できた音声メモを追加する */
    fun stopVoiceMemo() {
        val target = memoTarget ?: return
        finishMemo()
        when (recorder.stop()) {
            VoiceMemoStop.Saved ->
                if (files.hasContent(target.uri)) {
                    eventChannel.trySend(CaptureEvent.Captured(target.uri, target.mimeType))
                } else {
                    files.discard(listOf(target.uri))
                }
            VoiceMemoStop.Failed -> {
                files.discard(listOf(target.uri))
                eventChannel.trySend(CaptureEvent.Message("録音が短すぎるか、残せませんでした"))
            }
        }
    }

    /** 録音を捨てる */
    fun cancelVoiceMemo() {
        val target = memoTarget ?: return
        finishMemo()
        recorder.cancel()
        files.discard(listOf(target.uri))
    }

    /** マイクの許可が得られなかった */
    fun onMicrophoneDenied() {
        eventChannel.trySend(CaptureEvent.Message("マイクの許可が無いため録音できません。端末の設定で許可できます"))
    }

    /**
     * 画面が見えなくなった。Android はバックグラウンドのアプリのマイクを止めるので、無音を録り続けないよう
     * そこまでの録音を残して終える。
     */
    fun onScreenHidden() {
        if (voiceMemoFlow.value is VoiceMemoState.Recording) stopVoiceMemo()
    }

    private fun failVoiceMemo(message: String) {
        val target = memoTarget ?: return
        finishMemo()
        recorder.cancel()
        files.discard(listOf(target.uri))
        eventChannel.trySend(CaptureEvent.Message(message))
    }

    private fun finishMemo() {
        memoJob?.cancel()
        memoJob = null
        memoTarget = null
        voiceMemoFlow.value = VoiceMemoState.Idle
    }

    override fun onCleared() {
        memoTarget?.let {
            recorder.cancel()
            files.discard(listOf(it.uri))
        }
        super.onCleared()
    }

    companion object {
        /** 追加しないまま残った一時ファイルを掃除するまでの時間 */
        const val STALE_MILLIS = 6L * 60 * 60 * 1000

        /** 経過時間と音の大きさを更新する間隔 */
        const val TICK_MILLIS = 100L

        /** 音声メモの長さの上限。保存後の文字起こしが読む長さ(1時間)に合わせる */
        const val MAX_MEMO_MILLIS = 60L * 60 * 1000
    }
}

/**
 * 追加シートが使う [CaptureViewModel] を差し替えるための口。通常は null で、Hilt が作ったものを使う。
 * Hilt の無い単体テスト(Robolectric)で追加シートを開くときに、仮の実装を渡す。
 */
val LocalCaptureViewModel = compositionLocalOf<CaptureViewModel?> { null }
