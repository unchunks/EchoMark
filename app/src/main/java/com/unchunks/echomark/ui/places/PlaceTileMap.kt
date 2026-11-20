package com.unchunks.echomark.ui.places

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.place.PlaceGroup
import com.unchunks.echomark.domain.place.PlacesTileMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * 端末がネットワークにつながっているか(検証済みのネットワークがあるか)。つながりが変わったら更新される。
 * 位置情報の許可は要らない(ネットワークの有無だけを見る)。
 */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(context.hasValidatedNetwork()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = context.hasValidatedNetwork() }
            override fun onLost(network: Network) { online = context.hasValidatedNetwork() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                online = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun Context.hasValidatedNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** 地図の右下に必ず出す帰属表示(OpenFreeMap・OpenMapTiles・OpenStreetMap。それぞれのページへのリンクつき)。 */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val text: AnnotatedString = buildAnnotatedString {
        PlacesTileMap.attribution.forEach { part ->
            if (part.url != null) {
                withLink(LinkAnnotation.Url(part.url, linkStyle)) { append(part.text) }
            } else {
                append(part.text)
            }
        }
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * タイルの地図の背景に、座標のあるまとまりの点を重ねて出す(MapLibre Native + OpenFreeMap)。
 *
 * - この画面が組まれている間だけ地図を持つ(画面を離れる・アプリが見えなくなると、地図の通信も止まる)。オフラインダウンロードはしない
 * - 点のデータ(GeoJSON)は端末の中の地図にだけ渡す。タイルのサーバーへ送るのは、表示する範囲のタイルの番号と User-Agent だけ
 * - スタイルが読めない・[PlacesTileMap.STYLE_TIMEOUT_SECONDS] 秒たっても読めないときは [onFailed] を呼ぶ(呼び出し側が分布図に戻す)
 * - 帰属表示は、地図の標準のボタンではなく [MapAttribution] を常に出す
 */
@Composable
fun PlaceTileMap(
    groups: List<PlaceGroup>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnFailed by rememberUpdatedState(onFailed)
    var styleLoaded by remember { mutableStateOf(false) }
    val mapState = remember { MapState() }
    val circleColor = MaterialTheme.colorScheme.primary.toArgb()
    val selectedColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val strokeColor = MaterialTheme.colorScheme.surface.toArgb()

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        installUserAgent(context)
        MapView(context).also { view ->
            view.addOnDidFailLoadingMapListener { message -> currentOnFailed(message.ifBlank { "style" }) }
            view.getMapAsync { map ->
                mapState.map = map
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.setStyle(Style.Builder().fromUri(PlacesTileMap.STYLE_URL)) { style ->
                    style.addSource(GeoJsonSource(PlacesTileMap.SOURCE_ID, mapState.json))
                    style.addLayer(
                        CircleLayer(PlacesTileMap.LAYER_ID, PlacesTileMap.SOURCE_ID).withProperties(
                            PropertyFactory.circleRadius(
                                Expression.sum(Expression.literal(7), Expression.product(Expression.literal(2), Expression.sqrt(Expression.get("count"))))
                            ),
                            PropertyFactory.circleColor(
                                Expression.switchCase(Expression.toBool(Expression.get("selected")), Expression.color(mapState.selectedColor), Expression.color(mapState.circleColor))
                            ),
                            PropertyFactory.circleOpacity(0.9f),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor(mapState.strokeColor)
                        )
                    )
                    mapState.fit(map)
                    styleLoaded = true
                }
                map.addOnMapClickListener { latLng ->
                    val screen = map.projection.toScreenLocation(latLng)
                    val hit = map.queryRenderedFeatures(screen, PlacesTileMap.LAYER_ID).firstOrNull()
                    currentOnSelect(hit?.getStringProperty("key"))
                    true
                }
            }
        }
    }
    mapState.circleColor = circleColor
    mapState.selectedColor = selectedColor
    mapState.strokeColor = strokeColor
    mapState.groups = groups

    // 点の更新(選んだものを変えたとき・ブックマークが変わったとき)。カメラは最初の 1 回だけ合わせる
    LaunchedEffect(groups, selectedKey, styleLoaded) {
        mapState.json = PlacesTileMap.featureCollection(groups, selectedKey)
        mapState.map?.getStyle { style ->
            style.getSourceAs<GeoJsonSource>(PlacesTileMap.SOURCE_ID)?.setGeoJson(mapState.json)
        }
    }

    // 時間内に読めなければ失敗とする
    LaunchedEffect(Unit) {
        delay(PlacesTileMap.STYLE_TIMEOUT_SECONDS * 1_000)
        if (!styleLoaded) currentOnFailed("timeout")
    }

    // 地図のライフサイクルは、画面(Compose の組み込み)と、アプリが見えているかに合わせる。組みから外れたら、通信も止まる
    DisposableEffect(lifecycle, mapView) {
        var created = false
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) { mapView.onCreate(null); created = true }
                Lifecycle.Event.ON_START -> if (created && !started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (started && !resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        if (!created) { mapView.onCreate(null); created = true }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { contentDescription = "地図の背景つきの分布図。下の一覧でも同じ内容を見られます" }
        )
        MapAttribution(Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

/** 地図の中で使う値(Compose の再構成をまたいで持つ)。 */
private class MapState {
    var map: MapLibreMap? = null
    var json: String = PlacesTileMap.featureCollection(emptyList(), null)
    var groups: List<PlaceGroup> = emptyList()
    var circleColor: Int = 0
    var selectedColor: Int = 0
    var strokeColor: Int = 0
    private var fitted = false

    /** 点がすべて見える範囲に、最初の 1 回だけカメラを合わせる。 */
    fun fit(map: MapLibreMap) {
        if (fitted) return
        val points: List<GeoPoint> = groups.mapNotNull { it.center }
        val bounds = PlacesTileMap.cameraBounds(points) ?: return
        fitted = true
        val update = if (bounds.isSinglePoint) {
            CameraUpdateFactory.newLatLngZoom(LatLng(bounds.centerLatitude, bounds.centerLongitude), PlacesTileMap.SINGLE_POINT_ZOOM)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().include(LatLng(bounds.south, bounds.west)).include(LatLng(bounds.north, bounds.east)).build(),
                FIT_PADDING_PX
            )
        }
        map.moveCamera(update)
    }

    private companion object {
        const val FIT_PADDING_PX = 96
    }
}

/** 地図の通信に使う、アプリの共通の OkHttpClient(オフラインモードのインターセプターつき)を取り出す */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MapNetworkEntryPoint {
    fun okHttpClient(): OkHttpClient
}

/**
 * 地図(MapLibre)の通信を、アプリの共通の OkHttpClient から作った client にする(オフラインモードで断られるように。
 * ライブラリの既定の client はオフラインモードを知らない)。タイルを取るときの User-Agent は、アプリ名とバージョンにする
 * (ライブラリの既定のままにしない)。送るのはこれだけで、ブックマークのデータは付けない。
 */
private fun installUserAgent(context: Context) {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    val userAgent = "EchoMark/$version (Android; +https://github.com/unchunks/EchoMark)".replace("/ (", "/unknown (")
    val shared = EntryPointAccessors.fromApplication(context.applicationContext, MapNetworkEntryPoint::class.java).okHttpClient()
    HttpRequestUtil.setOkHttpClient(
        shared.newBuilder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
    )
}
