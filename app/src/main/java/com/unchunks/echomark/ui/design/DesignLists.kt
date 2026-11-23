package com.unchunks.echomark.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.softRaised

/** 行のまとまりの中の位置。先頭と末尾で角の丸め方・区切り線を変える */
@Immutable
data class GroupPosition(val first: Boolean, val last: Boolean) {
    companion object {
        /** [count] 件のまとまりの [index] 番目 */
        fun of(index: Int, count: Int): GroupPosition = GroupPosition(first = index == 0, last = index == count - 1)
    }
}

/**
 * 一覧の1行の入れ物(設定の行・タグやコレクションなどの管理の一覧の行)。デザインごとにまとまりを描き分ける。
 * - クラシック: 入れ物なし(これまでどおり、行を並べるだけ)
 * - エコー: トーナルな面を 2dp の隙間で区切り、まとまりの外側の角だけ大きく丸める(M3 Expressive の一覧)
 * - ペーパー: 入れ物なし。行の間に罫線を引く
 * - クリア: 白いまとまり。外側の角だけ丸め、行の間は文字の頭からの細い区切り線(iPhone の一覧)
 * - ソフト: 1行ずつ押し出した札にする
 *
 * 行のタップの波紋はまとまりの形に切り抜く。
 * @param horizontalInset クラシック・ペーパー以外で、まとまりを画面の端から離す幅。一覧に左右の余白が
 *   すでにある(設定の一覧など)ときは 0
 * @param dividerStart クリアの区切り線の始まり(先頭のアイコンがある行は、行の左の余白 16dp + アイコン 24dp + 間 16dp)
 */
@Composable
fun GroupedRow(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
    dividerStart: Dp = 56.dp,
    content: @Composable () -> Unit
) {
    val c = MaterialTheme.colorScheme
    when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC -> Box(modifier) { content() }
        DesignStyle.ECHO -> {
            val shape = groupShape(position, outer = EchoMarkDesign.corner(20.dp), inner = 4.dp)
            Box(
                modifier
                    .padding(horizontal = horizontalInset)
                    .padding(top = if (position.first) 0.dp else 2.dp)
                    .clip(shape)
                    .background(c.surfaceContainer)
            ) { content() }
        }
        DesignStyle.PAPER -> Column(modifier) {
            content()
            if (!position.last) HorizontalDivider(Modifier.padding(start = 16.dp), color = c.outlineVariant)
        }
        DesignStyle.CLEAR -> {
            val shape = groupShape(position, outer = EchoMarkDesign.corner(16.dp), inner = 0.dp)
            Box(
                modifier
                    .padding(horizontal = horizontalInset)
                    .clip(shape)
                    .background(c.surfaceContainerLow)
            ) {
                Column {
                    content()
                    if (!position.last) HorizontalDivider(Modifier.padding(start = dividerStart), color = c.outlineVariant)
                }
            }
        }
        DesignStyle.SOFT -> {
            val shape = MaterialTheme.shapes.medium
            Box(
                modifier
                    .padding(horizontal = horizontalInset, vertical = 6.dp)
                    .softRaised(shape, EchoMarkDesign.isDark, depth = 4.dp)
                    .clip(shape)
                    .background(c.surface)
            ) { content() }
        }
    }
}

/** まとまりの外側の角を [outer]、内側の角を [inner] にした形 */
private fun groupShape(position: GroupPosition, outer: Dp, inner: Dp): RoundedCornerShape {
    val top = if (position.first) outer else inner
    val bottom = if (position.last) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}
