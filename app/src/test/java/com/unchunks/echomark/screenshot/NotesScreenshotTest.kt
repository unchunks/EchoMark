package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.BookmarkNote
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.detail.BookmarkDetailCallbacks
import com.unchunks.echomark.ui.detail.BookmarkDetailContent
import com.unchunks.echomark.ui.detail.BookmarkDetailUiState
import com.unchunks.echomark.ui.detail.NoteEditorSheetContent
import com.unchunks.echomark.ui.detail.NotesSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ノート・注釈の欄(詳細画面の中・単独)と編集シートの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val day = 24 * 60 * 60 * 1000L
    private val notes = listOf(
        BookmarkNote(
            id = 1, bookmarkId = 1, text = "不要な再コンポーズを見つける手順は、チームの勉強会でも共有したい。",
            quote = "まずは Layout Inspector で再コンポーズ回数を確認しましょう。", createdAt = PreviewSamples.NOW - 3 * day
        ),
        BookmarkNote(
            id = 2, bookmarkId = 1, text = "derivedStateOf の使いどころをあとで調べる。", createdAt = PreviewSamples.NOW - 2 * day,
            updatedAt = PreviewSamples.NOW - 1 * day
        )
    )

    @Test
    fun section() = screenshot.captureScreenLightDark("notes_section") {
        NotesSection(notes = notes, nowMillis = PreviewSamples.NOW, onAdd = {}, onEdit = {}, onDelete = {})
    }

    @Test
    fun sectionEmpty() = screenshot.captureScreenLightDark("notes_section_empty") {
        NotesSection(notes = emptyList(), nowMillis = PreviewSamples.NOW, onAdd = {}, onEdit = {}, onDelete = {})
    }

    @Test
    fun inDetail() = screenshot.captureScreenLightDark("notes_in_detail", heightDp = 1400) {
        BookmarkDetailContent(
            uiState = BookmarkDetailUiState(isLoading = false, bookmark = PreviewSamples.urlBookmark, notes = notes),
            snackbarHostState = SnackbarHostState(),
            callbacks = BookmarkDetailCallbacks(),
            nowMillis = PreviewSamples.NOW
        )
    }

    @Test
    fun editorWithQuote() = screenshot.captureScreenLightDark("notes_editor_quote", heightDp = 700) {
        NoteEditorSheetContent(
            isNew = true, text = "", quote = "まずは Layout Inspector で再コンポーズ回数を確認しましょう。",
            onTextChange = {}, onQuoteChange = {}, onCancel = {}, onSave = {}
        )
    }

    @Test
    fun editorEditing() = screenshot.captureScreenLightDark("notes_editor_edit", heightDp = 600) {
        NoteEditorSheetContent(
            isNew = false, text = "derivedStateOf の使いどころをあとで調べる。", quote = null,
            onTextChange = {}, onQuoteChange = {}, onCancel = {}, onSave = {}
        )
    }
}
