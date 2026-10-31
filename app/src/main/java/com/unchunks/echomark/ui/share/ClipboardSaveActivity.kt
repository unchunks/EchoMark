package com.unchunks.echomark.ui.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.MainViewModel
import com.unchunks.echomark.ui.bookmark.readClipboard
import com.unchunks.echomark.ui.navigation.LaunchTarget
import dagger.hilt.android.AndroidEntryPoint

/**
 * クイック設定のタイル「クリップボードを保存」から開くActivity。クリップボードの URL・テキスト・画像を、
 * 共有のときと同じクイック保存のシート([QuickSaveSheet])に出して保存する。
 * クリップボードを読むのは、利用者がタイルを押してこの画面が前面に出たときの1回だけ(バックグラウンドでは読まない。
 * Android 10 以降は、前面にあって入力フォーカスがあるアプリしか読めない)。
 * 保存できるものがクリップボードに無ければ、追加シートを開く。
 */
@AndroidEntryPoint
class ClipboardSaveActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()
    private val appViewModel: MainViewModel by viewModels()

    /** クリップボードを読み終えた(回転などで作り直されても、もう一度は読まない) */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(STATE_HANDLED) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_HANDLED, handled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 入力フォーカスを得るまでは、クリップボードを読めない
        if (!hasFocus || handled) return
        handled = true
        val shared = readClipboard(this).toSharedContent()?.withFileDetails(contentResolver)
        if (shared == null) {
            openAddSheet()
            finish()
            return
        }
        setQuickSaveContent(shared, viewModel, appViewModel)
    }

    /** 保存できるものが無かったので、アプリの追加シートを開いて入力してもらう */
    private fun openAddSheet() {
        startActivity(
            LaunchTarget.putInto(Intent(this, MainActivity::class.java), LaunchTarget.ADD_BOOKMARK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private companion object {
        const val STATE_HANDLED = "handled"
    }
}
