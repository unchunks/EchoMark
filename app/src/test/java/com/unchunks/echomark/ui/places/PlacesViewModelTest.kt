package com.unchunks.echomark.ui.places

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.repository.CurrentLocationProvider
import com.unchunks.echomark.domain.repository.CurrentLocationResult
import com.unchunks.echomark.domain.repository.PlaceDetection
import com.unchunks.echomark.domain.repository.PlaceRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakePlaceRepository : PlaceRepository {
    val saved = mutableListOf<Triple<Long, String?, GeoPoint?>>()
    var detection = PlaceDetection(0, 0, 0)
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null
    var detectCalls = 0

    override suspend fun setPlace(bookmarkId: Long, name: String?, point: GeoPoint?) {
        failure?.let { throw it }
        saved += Triple(bookmarkId, name, point)
    }

    override suspend fun detectFromExisting(): PlaceDetection {
        detectCalls++
        gate?.await()
        failure?.let { throw it }
        return detection
    }
}

private class FakeCurrentLocation(var result: CurrentLocationResult) : CurrentLocationProvider {
    var calls = 0
    override suspend fun current(): CurrentLocationResult {
        calls++
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val bookmarks = FakeBookmarkRepository()
    private val places = FakePlaceRepository()

    private fun bookmark(id: Long, lat: Double? = null, lng: Double? = null, place: String? = null) = Bookmark(
        id = id, type = BookmarkType.URL, title = "題$id", createdAt = id, lastAccessedAt = id,
        latitude = lat, longitude = lng, placeName = place
    )

    private fun TestScope.viewModel(): PlacesViewModel {
        val vm = PlacesViewModel(bookmarks, places, TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)))
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun 場所のあるものだけをまとめて数える() = runTest {
        bookmarks.bookmarks.value = listOf(
            bookmark(1, 35.0, 139.0, "A"), bookmark(2, 35.1, 139.1, "A"), bookmark(3, place = "名前だけ"),
            bookmark(4, 10.0, 20.0), bookmark(5)
        )
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(5, state.totalCount)
        assertEquals(3, state.locatedCount)
        assertEquals(1, state.nameOnlyCount)
        assertEquals(3, state.groups.size)
        assertEquals("A", state.groups.first().label)
    }

    @Test
    fun ブックマークが変わると追従する() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.groups.isEmpty())

        bookmarks.bookmarks.value = listOf(bookmark(1, 35.0, 139.0))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.locatedCount)
    }

    @Test
    fun 選んだまとまりは_消えたら選択を外す() = runTest {
        bookmarks.bookmarks.value = listOf(bookmark(1, place = "A"), bookmark(2, place = "B"))
        val vm = viewModel()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.first { it.label == "A" }.key
        vm.select(key)
        advanceUntilIdle()
        assertEquals(key, vm.uiState.value.selectedKey)

        bookmarks.bookmarks.value = listOf(bookmark(2, place = "B"))
        advanceUntilIdle()
        assertNull(vm.uiState.value.selectedKey)
    }

    @Test
    fun 場所を探すと結果が出て_実行中は続けて押せない() = runTest {
        val gate = CompletableDeferred<Unit>()
        places.gate = gate
        places.detection = PlaceDetection(checked = 4, fromLinks = 1, fromPhotos = 2)
        val vm = viewModel()
        advanceUntilIdle()

        vm.detect()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.detecting)
        vm.detect()
        advanceUntilIdle()
        assertEquals(1, places.detectCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.detecting)
        assertEquals(PlaceDetection(4, 1, 2), vm.uiState.value.lastDetection)
    }

    @Test
    fun 場所を探すのに失敗したら_エラーを出して実行中を終える() = runTest {
        places.failure = IllegalStateException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        vm.detect()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.detecting)
        assertNotNull(vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.lastDetection)
    }

    @Test
    fun 場所を探す結果の文は件数に応じて変わる() {
        assertTrue(PlaceDetection(0, 0, 0).summaryText().contains("調べるリンク・写真がありません"))
        assertTrue(PlaceDetection(3, 0, 0).summaryText().contains("見つかりませんでした"))
        assertEquals("5件を調べて、3件の場所を入れました(リンク 1件・写真 2件)", PlaceDetection(5, 1, 2).summaryText())
    }

    // ---- 場所を直す操作 ----

    @Test
    fun 場所の保存と消去はブックマークの場所の書き込みになる() = runTest {
        val vm = PlaceEditViewModel(places, FakeCurrentLocation(CurrentLocationResult.Unavailable))
        vm.save(7, "名前", GeoPoint(1.0, 2.0))
        vm.clear(7)
        advanceUntilIdle()
        assertEquals(listOf(Triple(7L, "名前", GeoPoint(1.0, 2.0)), Triple(7L, null, null)), places.saved)
    }

    @Test
    fun 保存に失敗しても落ちない() = runTest {
        places.failure = IllegalStateException("boom")
        val vm = PlaceEditViewModel(places, FakeCurrentLocation(CurrentLocationResult.Unavailable))
        vm.save(7, "名前", null)
        advanceUntilIdle()
        assertTrue(places.saved.isEmpty())
    }

    @Test
    fun 現在地は呼ばれたときだけ調べる() = runTest {
        val provider = FakeCurrentLocation(CurrentLocationResult.Found(GeoPoint(35.0, 139.0)))
        val vm = PlaceEditViewModel(places, provider)
        // 作っただけ・保存しただけでは調べない
        vm.save(1, "x", null)
        advanceUntilIdle()
        assertEquals(0, provider.calls)

        assertEquals(CurrentLocationResult.Found(GeoPoint(35.0, 139.0)), vm.currentLocation())
        assertEquals(1, provider.calls)
    }
}
