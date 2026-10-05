package com.unchunks.echomark.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 設定・フォーム・一覧の本文に使う、読みやすい幅の上限。
 *
 * タブレットや折りたたみの展開時(幅 900dp 超)にスマートフォン用のレイアウトをそのまま広げると、
 * 1行が長すぎて読みにくく、ボタンや値が画面の両端に離れてしまう。そこで本文の幅に上限を設けて中央に寄せる。
 * 640dp は Material 3 のボトムシートの最大幅(`BottomSheetDefaults.SheetMaxWidth`)と同じで、
 * 左右 16dp の余白を引いた約 600dp が日本語(16sp)で 1 行およそ 37 字になり、読みやすい行長(30〜40 字)に収まる。
 * Material 3 の Medium ウィンドウ幅(600dp〜)の入り口でもあり、スマートフォンの横向き程度までは見た目が変わらない。
 */
val ReadableMaxWidth: Dp = 640.dp

/**
 * 幅を [maxWidth] までに制限し、画面の中央に寄せる。
 * 幅が [maxWidth] 以下なら今までどおり親の幅いっぱいに広がる(スマートフォンでは何も変わらない)。
 *
 * スクロールする内容に使うときは、スクロール領域自体は画面幅いっぱいのままにしたいので、
 * `Modifier.verticalScroll(...)` の付いた親の **内側** の Column などに付ける。
 * [LazyColumn] の場合は [ReadableLazyColumn] を使う。
 */
fun Modifier.readableWidth(maxWidth: Dp = ReadableMaxWidth): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()

/**
 * 項目の幅を [maxWidth] までに制限して中央に寄せる [LazyColumn]。
 *
 * [LazyColumn] 自体は親の大きさいっぱいのままなので、余白の部分をスワイプしてもスクロールでき、
 * オーバースクロールの効果も画面全体に出る。広い画面では左右の余白(contentPadding)だけを足して、項目を中央に寄せる。
 * 行をタップしたときの波紋も [maxWidth] の範囲に収まる。
 *
 * @param contentPadding 通常の余白(システムバーのインセットなど)。広い画面ではこれに左右の余白が加わる
 */
@Composable
fun ReadableLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    maxWidth: Dp = ReadableMaxWidth,
    content: LazyListScope.() -> Unit
) {
    BoxWithConstraints(modifier) {
        val layoutDirection = LocalLayoutDirection.current
        val start = contentPadding.calculateStartPadding(layoutDirection)
        val end = contentPadding.calculateEndPadding(layoutDirection)
        // 既存の余白を引いた残りが maxWidth を超える分を、左右に半分ずつ足す
        val extra = ((this.maxWidth - start - end - maxWidth) / 2).coerceAtLeast(0.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(
                start = start + extra,
                end = end + extra,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding()
            ),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
