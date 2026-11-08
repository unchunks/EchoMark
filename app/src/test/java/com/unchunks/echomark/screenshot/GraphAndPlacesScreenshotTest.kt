package com.unchunks.echomark.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.graph.ForceLayout
import com.unchunks.echomark.domain.graph.GraphOptions
import com.unchunks.echomark.domain.graph.KnowledgeGraphBuilder
import com.unchunks.echomark.domain.place.Places
import com.unchunks.echomark.domain.repository.PlaceDetection
import com.unchunks.echomark.ui.components.PreviewSamples
import com.unchunks.echomark.ui.graph.GraphContent
import com.unchunks.echomark.ui.graph.GraphMode
import com.unchunks.echomark.ui.graph.GraphUiState
import com.unchunks.echomark.ui.places.PlaceCallbacks
import com.unchunks.echomark.ui.places.PlaceSection
import com.unchunks.echomark.ui.places.PlacesContent
import com.unchunks.echomark.ui.places.PlacesUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ナレッジグラフ・場所の見た目(図・一覧・空・分布図・詳細の場所の欄)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphAndPlacesScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    // ---- ナレッジグラフ ----

    private val topics = listOf(
        "Android" to listOf("Compose", "Kotlin"), "料理" to listOf("和食", "レシピ"), "旅行" to listOf("京都", "写真")
    )

    private val graphBookmarks: List<Bookmark> = (1..24).map { i ->
        val (main, subs) = topics[i % topics.size]
        Bookmark(
            id = i.toLong(), type = BookmarkType.URL, title = "$main の記事 $i", createdAt = i.toLong(), lastAccessedAt = i.toLong(),
            tags = listOf(main, subs[i % subs.size])
        )
    }

    private val graph = KnowledgeGraphBuilder.build(
        graphBookmarks,
        groups = listOf((1L..24L).filter { it % 3 == 0L }.take(6)),
        options = GraphOptions(maxNodes = 40)
    )

    private val graphState = GraphUiState(
        isLoading = false,
        graph = graph,
        layout = ForceLayout.layout(graph),
        filterTags = listOf("Android", "料理", "旅行", "Kotlin"),
        bookmarkCount = graphBookmarks.size
    )

    private fun graphContent(state: GraphUiState, mode: GraphMode): @androidx.compose.runtime.Composable () -> Unit = {
        GraphContent(
            uiState = state, onBack = {}, onOpenBookmark = {}, onRefresh = {}, onSelect = {}, onMaxNodes = {}, onTagFilter = {},
            initialMode = mode
        )
    }

    @Test
    fun graph() = screenshot.captureScreenLightDark("graph_canvas", content = graphContent(graphState, GraphMode.GRAPH))

    @Test
    fun graphSelected() = screenshot.captureScreenLightDark(
        "graph_selected",
        content = graphContent(graphState.copy(selectedId = graph.nodes.first().id), GraphMode.GRAPH)
    )

    @Test
    fun graphList() = screenshot.captureScreenLightDark(
        "graph_list",
        content = graphContent(graphState.copy(selectedId = graph.nodes.first().id), GraphMode.LIST)
    )

    @Test
    fun graphEmpty() = screenshot.captureScreenLightDark(
        "graph_empty",
        content = graphContent(GraphUiState(isLoading = false, indexIncomplete = true), GraphMode.GRAPH)
    )

    // ---- 場所 ----

    private fun placed(id: Long, lat: Double?, lng: Double?, place: String?) = PreviewSamples.urlBookmark.copy(
        id = id, title = "場所のブックマーク $id", latitude = lat, longitude = lng, placeName = place
    )

    private val placeBookmarks = listOf(
        placed(1, 35.6812, 139.7671, "東京駅"), placed(2, 35.6809, 139.7673, "東京駅"), placed(3, 35.0116, 135.7681, "京都"),
        placed(4, 34.6937, 135.5023, null), placed(5, 43.0621, 141.3544, "札幌"), placed(6, null, null, "名前だけの場所")
    )

    private val placesState = PlacesUiState(
        isLoading = false,
        groups = Places.group(placeBookmarks),
        locatedCount = 5,
        nameOnlyCount = 1,
        totalCount = 12,
        lastDetection = PlaceDetection(checked = 6, fromLinks = 1, fromPhotos = 2)
    )

    /** タイルの地図の代わりの仮の描画(地図のライブラリはこのテストでは動かさない)。帰属表示は本物。 */
    private val fakeTileMap: com.unchunks.echomark.ui.places.TileMapSlot = { _, _, _, _, modifier ->
        androidx.compose.foundation.layout.Box(
            modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color(0xFFCFE3F0))
        ) {
            com.unchunks.echomark.ui.places.MapAttribution(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(4.dp))
        }
    }

    private fun placesContent(state: PlacesUiState, online: Boolean = true): @androidx.compose.runtime.Composable () -> Unit = {
        PlacesContent(
            uiState = state, onBack = {}, onOpenBookmark = {}, onSelect = {}, onDetect = {}, onOpenInMap = { _, _ -> },
            isOnline = online, tileMap = fakeTileMap
        )
    }

    @Test
    fun places() = screenshot.captureScreenLightDark("places_map", content = placesContent(placesState))

    @Test
    fun placesSelected() = screenshot.captureScreenLightDark(
        "places_selected",
        content = placesContent(placesState.copy(selectedKey = placesState.groups.first().key))
    )


    @Test
    fun placesTileMap() = screenshot.captureScreenLightDark(
        "places_tile_map", content = placesContent(placesState.copy(mapBackgroundEnabled = true))
    )

    @Test
    fun placesTileMapOffline() = screenshot.captureScreenLightDark(
        "places_tile_map_offline", content = placesContent(placesState.copy(mapBackgroundEnabled = true), online = false)
    )
    /** オフラインモードの間は地図の背景を出さず、分布図にして理由を添える */
    @Test
    fun placesTileMapOfflineMode() = screenshot.captureScreenLightDark(
        "places_tile_map_offline_mode", content = placesContent(placesState.copy(mapBackgroundEnabled = true, offlineMode = true))
    )

    @Test
    fun placesEmpty() = screenshot.captureScreenLightDark("places_empty", content = placesContent(PlacesUiState(isLoading = false)))

    @Test
    fun placeSection() = screenshot.captureLightDark("place_section") {
        Column(Modifier.padding(16.dp)) {
            PlaceSection(placeBookmarks[0], PlaceCallbacks())
            PlaceSection(PreviewSamples.urlBookmark, PlaceCallbacks(), Modifier.padding(top = 24.dp))
        }
    }
}
