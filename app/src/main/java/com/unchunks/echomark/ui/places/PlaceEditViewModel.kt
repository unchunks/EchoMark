package com.unchunks.echomark.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.repository.CurrentLocationProvider
import com.unchunks.echomark.domain.repository.CurrentLocationResult
import com.unchunks.echomark.domain.repository.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 詳細画面で場所を直す操作。どのブックマークかは呼ぶときに渡す(詳細画面の ViewModel とは別にして、依存を増やさない)。
 * 現在地は [currentLocation] が呼ばれたときだけ調べる。許可を求めるのは、呼ぶ前に画面が行う。
 */
@HiltViewModel
class PlaceEditViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val currentLocationProvider: CurrentLocationProvider
) : ViewModel() {

    /** 場所を書く(座標・名前のどちらかだけでもよい)。どちらも null なら消す。 */
    fun save(bookmarkId: Long, name: String?, point: GeoPoint?) {
        viewModelScope.launch {
            try {
                placeRepository.setPlace(bookmarkId, name, point)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "場所を保存できなかった")
            }
        }
    }

    fun clear(bookmarkId: Long) = save(bookmarkId, null, null)

    /** 現在地を 1 回調べる。 */
    suspend fun currentLocation(): CurrentLocationResult = currentLocationProvider.current()
}
