package com.unchunks.echomark.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.place.PlaceGroup
import com.unchunks.echomark.domain.place.Places
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.PlaceDetection
import com.unchunks.echomark.domain.repository.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class PlacesUiState(
    val isLoading: Boolean = true,
    val groups: List<PlaceGroup> = emptyList(),
    /** 座標のあるブックマークの数(地図に点が出るもの) */
    val locatedCount: Int = 0,
    /** 名前だけで座標の無いブックマークの数(一覧には出るが地図には出ない) */
    val nameOnlyCount: Int = 0,
    val totalCount: Int = 0,
    val selectedKey: String? = null,
    /** 「場所を探す」の実行中 */
    val detecting: Boolean = false,
    /** 直前の「場所を探す」の結果(まだ押していなければ null) */
    val lastDetection: PlaceDetection? = null,
    /** 「地図の背景」のスイッチ(オンの間、画面を開いていればタイルの地図を出す。既定はオフ) */
    val mapBackgroundEnabled: Boolean = false,
    /** オフラインモード(オンの間は地図の背景を読まない) */
    val offlineMode: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 場所のあるブックマークの地図・一覧。「場所を探す」は利用者が押したときだけ、座標の無いリンクと写真を調べる。
 * 集計は [DispatcherProvider.default] で行う。
 */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    bookmarkRepository: BookmarkRepository,
    private val placeRepository: PlaceRepository,
    private val appSettings: AppSettingsRepository,
    dispatchers: DispatcherProvider
) : ViewModel() {

    private data class Extras(
        val selectedKey: String? = null,
        val detecting: Boolean = false,
        val lastDetection: PlaceDetection? = null,
        val errorMessage: String? = null
    )

    private val extras = MutableStateFlow(Extras())

    val uiState: StateFlow<PlacesUiState> = combine(
        bookmarkRepository.observeBookmarks(), extras, appSettings.placesMapBackground, appSettings.offlineMode
    ) { bookmarks, extras, mapBackground, offlineMode ->
        val placed = bookmarks.filter { it.hasPlace }
        val groups = Places.group(placed)
        PlacesUiState(
            isLoading = false,
            groups = groups,
            locatedCount = placed.count { it.geoPoint != null },
            nameOnlyCount = placed.count { it.geoPoint == null },
            totalCount = bookmarks.size,
            selectedKey = extras.selectedKey?.takeIf { key -> groups.any { it.key == key } },
            detecting = extras.detecting,
            lastDetection = extras.lastDetection,
            mapBackgroundEnabled = mapBackground,
            offlineMode = offlineMode,
            errorMessage = extras.errorMessage
        )
    }
        .flowOn(dispatchers.default)
        .catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "場所の一覧に失敗")
            emit(PlacesUiState(isLoading = false, errorMessage = "場所を読み込めませんでした。"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    fun select(key: String?) = extras.update { it.copy(selectedKey = key) }

    /** 「地図の背景」のスイッチを切り替える(オンにしたときだけ、地図のタイルを読む) */
    fun setMapBackground(enabled: Boolean) {
        viewModelScope.launch { appSettings.setPlacesMapBackground(enabled) }
    }

    /** 座標の無いリンクと写真から、座標を探す。押されている間は続けて押せない。 */
    fun detect() {
        if (extras.value.detecting) return
        extras.update { it.copy(detecting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val result = placeRepository.detectFromExisting()
                extras.update { it.copy(detecting = false, lastDetection = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "場所を探せなかった")
                extras.update { it.copy(detecting = false, errorMessage = "場所を探せませんでした。") }
            }
        }
    }
}
