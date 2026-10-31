package com.unchunks.echomark.ui.capture

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider

import com.unchunks.echomark.domain.capture.CaptureKind

import com.unchunks.echomark.testing.FakeCaptureFiles
import com.unchunks.echomark.testing.FakeVoiceMemoRecorder
import com.unchunks.echomark.domain.capture.VoiceMemoStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val files = FakeCaptureFiles()
    private val recorder = FakeVoiceMemoRecorder()
    private val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)

    private fun createViewModel() = CaptureViewModel(files, recorder, dispatchers)

    @Test
    fun 開いたとき古い一時ファイルを掃除する() = runTest {
        createViewModel()
        runCurrent()

        assertEquals(CaptureViewModel.STALE_MILLIS, files.purgedOlderThan)
    }

    @Test
    fun 写真が撮れたら追加の知らせを出す() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        val target = viewModel.prepareCamera(CaptureKind.PHOTO)!!
        files.contents += target.uri
        viewModel.onCameraResult(target, CaptureKind.PHOTO, captured = true)
        runCurrent()

        assertEquals(listOf<CaptureEvent>(CaptureEvent.Captured(target.uri, "image/jpeg")), events)
        assertTrue(files.discarded.isEmpty())
    }

    @Test
    fun 撮影を取り消したら一時ファイルを捨てて何も知らせない() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        val target = viewModel.prepareCamera(CaptureKind.VIDEO)!!
        viewModel.onCameraResult(target, CaptureKind.VIDEO, captured = false)
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(listOf(target.uri), files.discarded)
    }

    @Test
    fun 撮れたと言われても中身が空なら追加しない() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        val target = viewModel.prepareCamera(CaptureKind.PHOTO)!!
        viewModel.onCameraResult(target, CaptureKind.PHOTO, captured = true)
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(listOf(target.uri), files.discarded)
    }

    @Test
    fun 一時ファイルを作れないときは理由を知らせる() = runTest {
        files.failCreate = true
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        assertNull(viewModel.prepareCamera(CaptureKind.PHOTO))
        runCurrent()

        assertTrue(events.single() is CaptureEvent.Message)
    }

    @Test
    fun 録音を始めると経過時間と音の大きさが更新される() = runTest {
        val viewModel = createViewModel()

        viewModel.startVoiceMemo()
        recorder.elapsed = 1_500L
        recorder.level = 0.4f
        advanceTimeBy(CaptureViewModel.TICK_MILLIS + 1)

        assertEquals(VoiceMemoState.Recording(1_500L, 0.4f), viewModel.voiceMemo.value)
        assertEquals(CaptureKind.VOICE_MEMO.mimeType, recorder.started!!.mimeType)
        viewModel.cancelVoiceMemo()
    }

    @Test
    fun 録音を止めると音声メモを追加して録音の状態が戻る() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        val target = recorder.started!!
        files.contents += target.uri
        viewModel.stopVoiceMemo()
        runCurrent()

        assertEquals(listOf<CaptureEvent>(CaptureEvent.Captured(target.uri, "audio/mp4")), events)
        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
    }

    @Test
    fun 短すぎて録音が残らなかったときは捨てて知らせる() = runTest {
        recorder.stopResult = VoiceMemoStop.Failed
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        val target = recorder.started!!
        viewModel.stopVoiceMemo()
        runCurrent()

        assertTrue(events.single() is CaptureEvent.Message)
        assertEquals(listOf(target.uri), files.discarded)
        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
    }

    @Test
    fun 録音を破棄すると一時ファイルを消し何も追加しない() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        val target = recorder.started!!
        viewModel.cancelVoiceMemo()
        runCurrent()

        assertTrue(events.isEmpty())
        assertTrue(recorder.cancelled)
        assertEquals(listOf(target.uri), files.discarded)
        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
    }

    @Test
    fun マイクを使えないときは録音の状態にならず理由を知らせる() = runTest {
        recorder.startSucceeds = false
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        runCurrent()

        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
        assertTrue(events.single() is CaptureEvent.Message)
        assertEquals(1, files.discarded.size)
    }

    @Test
    fun 録音が上限の長さに達したら自動で終えて追加する() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        files.contents += recorder.started!!.uri
        recorder.elapsed = CaptureViewModel.MAX_MEMO_MILLIS
        advanceTimeBy(CaptureViewModel.TICK_MILLIS + 1)
        runCurrent()

        assertTrue(events.single() is CaptureEvent.Captured)
        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
    }

    @Test
    fun 録音が途中で止まったら捨てて知らせる() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        recorder.fail()
        runCurrent()

        assertEquals(VoiceMemoState.Idle, viewModel.voiceMemo.value)
        assertTrue(events.single() is CaptureEvent.Message)
        assertTrue(recorder.cancelled)
    }

    @Test
    fun 画面が見えなくなったらそこまでの録音を残して終える() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.startVoiceMemo()
        files.contents += recorder.started!!.uri
        viewModel.onScreenHidden()
        runCurrent()

        assertTrue(events.single() is CaptureEvent.Captured)
        assertFalse(viewModel.voiceMemo.value is VoiceMemoState.Recording)
    }

    @Test
    fun 録音していないときに画面が見えなくなっても何もしない() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.onScreenHidden()
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun 録音中は二重に始めない() = runTest {
        val viewModel = createViewModel()

        viewModel.startVoiceMemo()
        viewModel.startVoiceMemo()

        assertEquals(1, recorder.startCount)
        viewModel.cancelVoiceMemo()
    }

    @Test
    fun マイクの許可が無いときは理由を知らせる() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.onMicrophoneDenied()
        runCurrent()

        assertTrue((events.single() as CaptureEvent.Message).text.contains("マイク"))
    }

    @Test
    fun クリップボードの画像を一時ファイルにコピーして追加する() = runTest {
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.importClipboardImage("content://clip/1", "image/png")
        runCurrent()

        assertEquals(listOf("content://clip/1" to "image/png"), files.copiedIn)
        val captured = events.single() as CaptureEvent.Captured
        assertEquals("image/png", captured.mimeType)
    }

    @Test
    fun クリップボードの画像をコピーできなければ理由を知らせる() = runTest {
        files.copyInSucceeds = false
        val viewModel = createViewModel()
        val events = mutableListOf<CaptureEvent>()
        backgroundScope.launch { viewModel.events.toList(events) }

        viewModel.importClipboardImage("content://clip/1", "image/png")
        runCurrent()

        assertTrue(events.single() is CaptureEvent.Message)
    }
}
