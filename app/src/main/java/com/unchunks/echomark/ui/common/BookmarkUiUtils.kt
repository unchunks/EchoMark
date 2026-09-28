package com.unchunks.echomark.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.unchunks.echomark.domain.bookmark.model.AiStatus

/** URLからドメイン(www.除去)を取り出す。取れなければURLをそのまま返す */
fun extractDomain(url: String): String =
    Uri.parse(url).host?.removePrefix("www.") ?: url

/** 既定のブラウザでURLを開く */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "URLを開けるアプリがありません", Toast.LENGTH_SHORT).show()
    }
}

/** 表示すべき AI ステータスの文言。完了(DONE)なら表示不要なので null */
fun AiStatus.displayLabel(): String? = when (this) {
    AiStatus.PENDING, AiStatus.PROCESSING -> "処理中"
    AiStatus.WAITING_MODEL -> "モデル待ち"
    AiStatus.FAILED -> "失敗"
    AiStatus.DONE -> null
}
