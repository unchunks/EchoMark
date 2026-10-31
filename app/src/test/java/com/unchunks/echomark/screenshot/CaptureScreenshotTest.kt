package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.bookmark.AddBookmarkForm
import com.unchunks.echomark.ui.bookmark.AddBookmarkSheetContent
import com.unchunks.echomark.ui.bookmark.AddMode
import com.unchunks.echomark.ui.bookmark.ClipboardHint
import com.unchunks.echomark.ui.capture.CaptureUiState
import com.unchunks.echomark.ui.capture.VoiceMemoState
import com.unchunks.echomark.ui.common.SelectedFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 追加シートの「撮る・録る」の見た目(写真・動画・録音のボタン、録音中、撮ったものが加わった状態)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val allAvailable = CaptureUiState(canTakePhoto = true, canTakeVideo = true, canRecordVoice = true)

    @Test
    fun captureButtons() = screenshot.captureLightDark("capture_buttons") {
        AddSheet(AddBookmarkForm(mode = AddMode.FILE), allAvailable)
    }

    @Test
    fun capturedFiles() = screenshot.captureLightDark("capture_captured_files") {
        val files = listOf(
            SelectedFile("content://x/captures/photo.jpg", "image/jpeg", "写真 2026-10-31 12-04-05.jpg", 2_400_000L),
            SelectedFile("content://x/captures/memo.m4a", "audio/mp4", "音声メモ 2026-10-31 12-05-10.m4a", 480_000L)
        )
        AddSheet(AddBookmarkForm(mode = AddMode.FILE, files = files), allAvailable)
    }

    @Test
    fun recording() = screenshot.captureLightDark("capture_recording") {
        AddSheet(
            AddBookmarkForm(mode = AddMode.FILE),
            allAvailable.copy(voiceMemo = VoiceMemoState.Recording(elapsedMillis = 65_000L, level = 0.45f))
        )
    }

    @Test
    fun microphoneDenied() = screenshot.captureLightDark("capture_microphone_denied") {
        AddSheet(
            AddBookmarkForm(mode = AddMode.FILE),
            allAvailable.copy(message = "マイクの許可が無いため録音できません。端末の設定で許可できます")
        )
    }

    @Test
    fun onlyMicrophone() = screenshot.captureLightDark("capture_only_microphone") {
        AddSheet(AddBookmarkForm(mode = AddMode.FILE), CaptureUiState(canRecordVoice = true))
    }

    @Test
    fun clipboardText() = screenshot.captureLightDark("clipboard_text") {
        AddSheet(AddBookmarkForm(mode = AddMode.NOTE), CaptureUiState(), ClipboardHint.TEXT, showClipboard = true)
    }

    @Test
    fun clipboardImage() = screenshot.captureLightDark("clipboard_image") {
        AddSheet(AddBookmarkForm(mode = AddMode.FILE), allAvailable, ClipboardHint.IMAGE, showClipboard = true)
    }

    @Test
    fun clipboardEmpty() = screenshot.captureLightDark("clipboard_empty") {
        AddSheet(
            AddBookmarkForm(mode = AddMode.LINK), CaptureUiState(), ClipboardHint.URL,
            showClipboard = true, clipboardMessage = "クリップボードに保存できるものがありません"
        )
    }

    @Composable
    private fun AddSheet(
        form: AddBookmarkForm,
        capture: CaptureUiState,
        clipboardHint: ClipboardHint = ClipboardHint.URL,
        showClipboard: Boolean = false,
        clipboardMessage: String? = null
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AddBookmarkSheetContent(
                form = form,
                showClipboardSuggestion = showClipboard,
                clipboardHint = clipboardHint,
                clipboardMessage = clipboardMessage,
                onModeChange = {},
                onUrlChange = {},
                onTitleChange = {},
                onMemoChange = {},
                onTextChange = {},
                onPasteFromClipboard = {},
                onCancel = {},
                onSave = {},
                capture = capture
            )
        }
    }
}
