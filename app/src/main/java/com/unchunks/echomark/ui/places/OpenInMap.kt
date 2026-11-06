package com.unchunks.echomark.ui.places

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.unchunks.echomark.domain.place.GeoPoint

/** 地図アプリで [point] を開く(geo: URI)。[title] はピンの名前。地図アプリが無ければ知らせる。 */
fun openInMapApp(context: Context, point: GeoPoint, title: String? = null) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, point.toGeoUri(title).toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "地図を開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}
