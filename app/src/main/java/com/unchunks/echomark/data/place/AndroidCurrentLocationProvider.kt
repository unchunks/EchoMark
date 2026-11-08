package com.unchunks.echomark.data.place

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.unchunks.echomark.domain.place.GeoPoint
import com.unchunks.echomark.domain.repository.CurrentLocationProvider
import com.unchunks.echomark.domain.repository.CurrentLocationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * 現在地を 1 回だけ調べる(LocationManager。Google Play 開発者サービスには頼らない)。
 * 利用者が「現在地を入れる」を押したときだけ呼ぶ。許可が無ければ [CurrentLocationResult.PermissionDenied] を返し、
 * 許可を求める画面は出さない(許可は呼び出し側の画面が、押されたあとに求める)。
 * ブックマークの場所には大まかな位置で足りるので、おおよその位置(ACCESS_COARSE_LOCATION)だけを使う。
 */
class AndroidCurrentLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : CurrentLocationProvider {

    override suspend fun current(): CurrentLocationResult {
        val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return CurrentLocationResult.PermissionDenied
        val manager = context.getSystemService(LocationManager::class.java) ?: return CurrentLocationResult.Unavailable
        return try {
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) }
                ?: return CurrentLocationResult.Unavailable
            val location = withTimeoutOrNull(TIMEOUT_MILLIS) { fix(manager, provider) }
                ?: manager.getLastKnownLocation(provider)
            location?.let { GeoPoint.of(it.latitude, it.longitude) }?.let { CurrentLocationResult.Found(it) }
                ?: CurrentLocationResult.Unavailable
        } catch (e: SecurityException) {
            // 調べている間に許可が取り消された
            CurrentLocationResult.PermissionDenied
        } catch (e: Exception) {
            Timber.w(e, "現在地を調べられなかった")
            CurrentLocationResult.Unavailable
        }
    }

    // 許可は current() が先に確かめ、取り消されたときの SecurityException もそちらで受けている
    @SuppressLint("MissingPermission")
    private suspend fun fix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
