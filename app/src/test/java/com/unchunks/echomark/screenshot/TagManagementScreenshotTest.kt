package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.ui.tags.TagManagementContent
import com.unchunks.echomark.ui.tags.TagManagementUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** タグ管理画面(一覧・空)の見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagManagementScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun list() = screenshot.captureScreenLightDark("library_tags") {
        TagManagementContent(
            uiState = TagManagementUiState(
                isLoading = false,
                tags = listOf(
                    TagWithCount(1, "Android", 12),
                    TagWithCount(2, "Compose", 8),
                    TagWithCount(3, "パフォーマンス", 3),
                    TagWithCount(4, "読書", 5),
                    TagWithCount(5, "とても長いタグ名の例: 機械学習とデータ分析の基礎から応用まで", 1),
                    TagWithCount(6, "未使用のタグ", 0)
                )
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenTag = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun empty() = screenshot.captureScreenLightDark("library_tags_empty") {
        TagManagementContent(
            uiState = TagManagementUiState(isLoading = false),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onOpenTag = {},
            onRename = { _, _ -> },
            onDelete = {}
        )
    }
}
