package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.capture.CaptureFiles
import com.unchunks.echomark.domain.capture.CaptureKind
import com.unchunks.echomark.domain.capture.CaptureTarget
import com.unchunks.echomark.domain.capture.VoiceMemoRecorder
import com.unchunks.echomark.domain.capture.VoiceMemoStop
import com.unchunks.echomark.ui.capture.CaptureViewModel
import com.unchunks.components.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers

class FakeCaptureFiles : CaptureFiles {
    val contents = mutableSetOf<String>()
    val discarded = mutableListOf<String>()
    var purgedOlderThan: Long? = null
    var failCreate = false
    private var counter = 0

    override fun create(kind: CaptureKind): CaptureTarget? {
        if (failCreate) return null
        counter++
        return CaptureTarget("content://test.fileprovider/captures/${kind.name}-$counter", kind.mimeType, "/cache/captures/${kind.name}-$counter")
    }

    override fun hasContent(uri: String) = uri in contents

    override fun discard(uris: Collection<String>) {
        discarded += uris
    }

    override fun purgeStale(olderThanMillis: Long) {
        purgedOlderThan = olderThanMillis
    }
}

class FakeVoiceMemoRecorder : VoiceMemoRecorder {
    var started: CaptureTarget? = null
    var startCount = 0
    var startSucceeds = true
    var stopResult: VoiceMemoStop = VoiceMemoStop.Saved
    var elapsed = 0L
    var level = 0f
    var cancelled = false
    private var onError: (() -> Unit)? = null

    override fun start(target: CaptureTarget, onError: () -> Unit): Boolean {
        startCount++
        if (!startSucceeds) return false
        started = target
        this.onError = onError
        return true
    }

    override fun elapsedMillis() = elapsed

    override fun level() = level

    override fun stop() = stopResult

    override fun cancel() {
        cancelled = true
    }

    fun fail() = onError!!.invoke()
}

/** Hilt の無いテストで追加シートに渡す、仮の実装で動く [CaptureViewModel] */
fun fakeCaptureViewModel() = CaptureViewModel(FakeCaptureFiles(), FakeVoiceMemoRecorder(), TestDispatcherProvider(Dispatchers.Unconfined))
