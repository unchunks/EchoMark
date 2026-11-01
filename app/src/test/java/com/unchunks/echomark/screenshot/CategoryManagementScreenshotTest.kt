package com.unchunks.echomark.screenshot

import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryNames
import com.unchunks.echomark.ui.categories.CategoryManagementContent
import com.unchunks.echomark.ui.categories.CategoryManagementUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** カテゴリ画面(自分のカテゴリあり・なし)の見た目。標準のカテゴリと、AI が付けたその他の分類も並べる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryManagementScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val builtIn = CategoryNames.BUILT_IN.take(4).mapIndexed { index, it ->
        CategoryEntry(it.name, it.description, CategoryKind.BUILT_IN, bookmarkCount = (4 - index) * 3)
    }
    private val other = CategoryEntry("料理教室", CategoryNames.AI_OTHER_DESCRIPTION, CategoryKind.AI_OTHER, 2)

    @Test
    fun withMine() = screenshot.captureScreenLightDark("library_categories") {
        CategoryManagementContent(
            uiState = CategoryManagementUiState(
                isLoading = false,
                entries = listOf(
                    CategoryEntry("旅行", "行き先や宿、持ち物など、出かける計画に使う情報", CategoryKind.USER, 5, id = 1),
                    CategoryEntry("読書メモ", "", CategoryKind.USER, 0, id = 2)
                ) + builtIn + other
            ),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onAdd = { _, _ -> },
            onUpdate = { _, _, _ -> },
            onDelete = {}
        )
    }

    @Test
    fun withoutMine() = screenshot.captureScreenLightDark("library_categories_empty") {
        CategoryManagementContent(
            uiState = CategoryManagementUiState(isLoading = false, entries = builtIn),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onAdd = { _, _ -> },
            onUpdate = { _, _, _ -> },
            onDelete = {}
        )
    }
}
