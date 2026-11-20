package com.unchunks.echomark.ui.places

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.place.GeoParser
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.repository.CurrentLocationResult
import kotlinx.coroutines.launch

/** 場所の操作。状態を持たない画面に渡す。 */
class PlaceCallbacks(
    /** 場所を書く(座標・名前のどちらかだけでもよい)。どちらも null なら消す */
    val onSave: (name: String?, point: GeoPoint?) -> Unit = { _, _ -> },
    val onClear: () -> Unit = {},
    /** 現在地を調べる(「現在地を入れる」を押したあと、許可が取れてから呼ぶ) */
    val fetchCurrentLocation: suspend () -> CurrentLocationResult = { CurrentLocationResult.Unavailable },
    /** 地図アプリで開く(座標と、ピンの名前) */
    val onOpenInMap: (GeoPoint, String?) -> Unit = { _, _ -> }
)

/**
 * 詳細画面の「場所」。座標・名前を見せ、地図アプリで開く・直す・消す。
 * 場所は、写真の位置情報・地図のリンク・利用者が入れたものだけから入る(裏で現在地を調べることはない)。
 */
@Composable
fun PlaceSection(bookmark: Bookmark, callbacks: PlaceCallbacks, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val point = bookmark.geoPoint
    val name = bookmark.placeName?.takeIf { it.isNotBlank() }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                "場所",
                modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { editing = true }) { Text(if (bookmark.hasPlace) "直す" else "場所を入れる") }
        }
        if (bookmark.hasPlace) {
            name?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            point?.let {
                Text(it.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (point != null) {
                    FilledTonalButton(onClick = { callbacks.onOpenInMap(point, name ?: bookmark.title) }) { Text("地図アプリで開く") }
                }
                OutlinedButton(onClick = callbacks.onClear) { Text("消す") }
            }
        } else {
            Text(
                "場所は入っていません。写真の位置情報や地図のリンクは、地図の画面の「場所を探す」で読み取れます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (editing) {
        PlaceSheet(
            initialName = bookmark.placeName.orEmpty(),
            initialPoint = point,
            callbacks = callbacks,
            onDismiss = { editing = false }
        )
    }
}

/** 場所を入れる・直すシート。座標は「緯度, 経度」か、地図のリンクを貼ってもよい。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceSheet(initialName: String, initialPoint: GeoPoint?, callbacks: PlaceCallbacks, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(initialName) }
    var coordinates by rememberSaveable { mutableStateOf(initialPoint?.label().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun fetchCurrent() {
        scope.launch {
            status = "現在地を調べています…"
            status = when (val result = callbacks.fetchCurrentLocation()) {
                is CurrentLocationResult.Found -> {
                    coordinates = result.point.label()
                    error = null
                    "現在地を入れました(おおよその位置)"
                }
                CurrentLocationResult.PermissionDenied -> "位置情報の許可がないため、現在地を入れられません"
                CurrentLocationResult.Unavailable -> "現在地を調べられませんでした。位置情報がオンか確かめてください"
            }
        }
    }

    // 許可は、「現在地を入れる」を押したときだけ求める
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchCurrent() else status = "位置情報の許可がないため、現在地を入れられません"
    }
    fun onCurrentLocationClick() {
        val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) fetchCurrent() else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("場所", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text("場所の名前(例: 〇〇カフェ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = coordinates,
                onValueChange = { coordinates = it; error = null },
                label = { Text("緯度, 経度(地図のリンクも貼れます)") },
                supportingText = { Text(error ?: "例: 35.6812, 139.7671") },
                isError = error != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = ::onCurrentLocationClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.MyLocation, contentDescription = null)
                Text("現在地を入れる", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                status ?: "このボタンを押したときだけ、おおよその位置を1回調べます(押すまで位置情報は使いません)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                FilledTonalButton(onClick = {
                    val text = coordinates.trim()
                    val point = if (text.isEmpty()) null else GeoParser.parseCoordinates(text) ?: GeoParser.fromUrl(text)
                    if (text.isNotEmpty() && point == null) {
                        error = "読み取れません。「35.6812, 139.7671」の形か、地図のリンクを入れてください"
                    } else {
                        callbacks.onSave(name.trim().ifEmpty { null }, point)
                        onDismiss()
                    }
                }) { Text("保存") }
            }
        }
    }
}

private const val MAX_NAME = 100
