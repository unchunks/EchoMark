package com.unchunks.echomark.ui.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.unchunks.echomark.R
import com.unchunks.echomark.ui.share.ClipboardSaveActivity

/**
 * クイック設定のタイル「クリップボードを保存」。押すと [ClipboardSaveActivity] を開き、クリップボードの URL・テキスト・画像を保存する。
 * タイル自体はクリップボードを読まない(読むのは、押されて画面が前面に出た [ClipboardSaveActivity] だけ)。
 */
class ClipboardSaveTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_clipboard_save_label)
            subtitle = getString(R.string.tile_clipboard_save_subtitle)
            updateTile()
        }
    }

    // Android 13 以前は PendingIntent を受け取る startActivityAndCollapse が無く、Intent を渡すしかない(Android 14 以降は PendingIntent の側を使う)
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSaveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
