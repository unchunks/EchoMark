package com.unchunks.echomark.ui.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.unchunks.echomark.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 他アプリの共有メニューから受け取ってクイック保存するActivity。
 * テキスト・URL(ACTION_SEND / ACTION_SEND_MULTIPLE, text/plain)と、ファイル(画像・PDF・音声・動画・テキストファイル。
 * ACTION_SEND / ACTION_SEND_MULTIPLE の EXTRA_STREAM)を受け付ける。
 * 文字を選んだときのメニュー(ACTION_PROCESS_TEXT。エイリアス「引用として EchoMark に保存」)からは、
 * 選んだ文章を引用として保存する。
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    /** テーマ設定の読み込み(アプリ本体と同じ) */
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ファイル名・サイズ・種類を提供元に問い合わせる(プレビューと保存前の確認に使う)
        val shared = parseSharedContent(intent)?.withFileDetails(contentResolver)
        if (shared == null) {
            Toast.makeText(this, "保存できる内容がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setQuickSaveContent(shared, viewModel, appViewModel)
    }
}
