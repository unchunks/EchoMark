package com.unchunks.echomark.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.softRaised

// 会話(AI とのチャット・使い方の質問)の見た目をデザインごとに描き分ける。
// 自分の発言は吹き出しにし、AI の回答は吹き出しにしない(長い回答を画面の幅いっぱいで読めるように。どのデザインでも同じ)。
//
// | デザイン  | 自分の吹き出し                                | AI のアイコン                 |
// | クラシック | 淡いインディゴ、右下だけ角を小さく             | 淡い色の丸                    |
// | エコー    | 淡いインディゴ、大きく丸め右下だけ小さく        | ティールの丸(AI の要約の印と同じ色) |
// | ペーパー  | クラフト紙の札(角の小さい四角)               | 墨の輪                        |
// | クリア    | 青の地に白い文字(iPhone のメッセージ)         | 灰色の丸に青い印              |
// | ソフト    | 地と同じ色を押し出した吹き出し                 | 地と同じ色を押し出した丸      |

/** 自分の発言の吹き出しの描き方 */
@Immutable
data class BubbleStyle(
    val color: Color,
    val contentColor: Color,
    val shape: Shape,
    val border: BorderStroke? = null,
    val depth: Modifier = Modifier
)

/**
 * 自分の発言(右寄せ)の吹き出し。右下の角を小さくして、話し手の側を示す。
 * @param classicShape クラシックでの形(これまでの形。使い方の質問の吹き出しは四隅とも丸い)
 */
@Composable
@ReadOnlyComposable
fun userBubbleStyle(
    classicShape: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
): BubbleStyle {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC -> BubbleStyle(c.primaryContainer, c.onPrimaryContainer, classicShape)
        DesignStyle.ECHO -> BubbleStyle(
            c.primaryContainer,
            c.onPrimaryContainer,
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 8.dp)
        )
        DesignStyle.PAPER -> BubbleStyle(c.primaryContainer, c.onPrimaryContainer, RoundedCornerShape(4.dp))
        DesignStyle.CLEAR -> BubbleStyle(
            c.primary,
            c.onPrimary,
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        )
        DesignStyle.SOFT -> {
            val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 8.dp)
            BubbleStyle(c.surface, c.onSurface, shape, depth = Modifier.softRaised(shape, EchoMarkDesign.isDark, depth = 4.dp))
        }
    }
}

/** AI のアイコン(丸)の描き方 */
@Immutable
data class AvatarStyle(
    val color: Color,
    val iconColor: Color,
    val border: BorderStroke? = null,
    val depth: Modifier = Modifier
) {
    val shape: Shape get() = CircleShape
}

@Composable
@ReadOnlyComposable
fun assistantAvatarStyle(): AvatarStyle {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC -> AvatarStyle(c.secondaryContainer, c.onSecondaryContainer)
        DesignStyle.ECHO -> AvatarStyle(c.tertiaryContainer, c.onTertiaryContainer)
        DesignStyle.PAPER -> AvatarStyle(Color.Transparent, c.primary, border = BorderStroke(1.dp, c.outline))
        DesignStyle.CLEAR -> AvatarStyle(c.surfaceContainerHigh, c.primary)
        DesignStyle.SOFT -> AvatarStyle(c.surface, c.primary, depth = Modifier.softRaised(CircleShape, EchoMarkDesign.isDark, depth = 3.dp))
    }
}
