package com.unchunks.echomark.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.softInset
import com.unchunks.echomark.ui.theme.softRaised

// 中立の入れ物(カード・パネル)をデザインごとに描き分ける。
// 画面は「何の入れ物か」([SurfaceRole])と「クラシックでの色」だけを渡し、描き方はここで決める。
// クラシックは渡された色をそのまま使うので、これまでの見た目は変わらない。
//
// | 役割      | エコー                    | ペーパー                     | クリア(iPhone 風)         | ソフト(ニューモフィズム) |
// | Card      | surfaceContainer          | 紙の地 + 罫線                | 白(ダークは灰)            | 地と同じ色を押し出す       |
// | Nested    | surfaceContainerHigh      | 一段濃い紙(罫線なし)        | 淡い灰色                  | 一段濃い地をくぼませる     |
// | Selected  | surfaceContainerHighest   | 濃い紙 + 濃い罫線            | 一段濃い灰色              | くぼませる(押した状態)   |

/** 中立の入れ物の役割。デザインごとの描き方はこの役割で決める。 */
enum class SurfaceRole {
    /** 画面やシートの上に置く、ひとまとまりの内容(ブックマーク・項目・情報のカード) */
    Card,

    /** カードやシートの中に置く、一段下がった区画(補足の枠・設定のまとまり・入力のまとまり) */
    Nested,

    /** 選んでいる・開いている項目(一覧の中の今の項目) */
    Selected
}

/**
 * 中立の入れ物の描き方。
 * @param raised ソフトの押し出し(外側の光と影)。要素の外に描くので、親が切り抜くと欠ける
 * @param inset ソフトのくぼみ(内側の光と影)
 * @param dark 地色が暗いか(影と光の色を選ぶ)
 */
@Immutable
data class ContainerStyle(
    val color: Color,
    val border: BorderStroke?,
    val raised: Boolean = false,
    val inset: Boolean = false,
    val dark: Boolean = false
)

/**
 * 役割 [role] の入れ物の描き方。[classic] はクラシックで使う色(これまでの色)。
 * 色はどれも MaterialTheme の配色のロールから選ぶので、ダイナミックカラーでも成り立つ。
 */
@Composable
@ReadOnlyComposable
fun containerStyle(role: SurfaceRole, classic: Color): ContainerStyle {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC -> ContainerStyle(classic, border = null)
        // トーナルな面の段差で区切る(枠線は使わない)
        DesignStyle.ECHO -> ContainerStyle(
            color = when (role) {
                SurfaceRole.Card -> c.surfaceContainer
                SurfaceRole.Nested -> c.surfaceContainerHigh
                SurfaceRole.Selected -> c.surfaceContainerHighest
            },
            border = null
        )
        // 紙の上に罫線で区切る。選んだものは紙を一段濃くし、罫線も濃くする
        DesignStyle.PAPER -> when (role) {
            SurfaceRole.Card -> ContainerStyle(c.surface, BorderStroke(1.dp, c.outlineVariant))
            SurfaceRole.Nested -> ContainerStyle(c.surfaceContainerLow, border = null)
            SurfaceRole.Selected -> ContainerStyle(c.surfaceContainerHigh, BorderStroke(1.dp, c.outline))
        }
        // 淡い灰色の地に白いまとまり。中の区画と選んだものは灰色の段で示す。
        // ダークで手前の面(シート)の上にいるときは、面が一段明るいので、カードもさらに一段明るくする
        DesignStyle.CLEAR -> {
            val lifted = EchoMarkDesign.isDark && LocalElevatedSurface.current
            ContainerStyle(
                color = when (role) {
                    SurfaceRole.Card -> if (lifted) c.surfaceContainerHigh else c.surfaceContainerLow
                    SurfaceRole.Nested -> if (lifted) c.surfaceContainerHighest else c.surfaceContainerHigh
                    SurfaceRole.Selected -> c.surfaceContainerHighest
                },
                border = null
            )
        }
        // 地と同じ色の面を、カードは押し出し、中の区画と選んだものはくぼませる
        DesignStyle.SOFT -> when (role) {
            SurfaceRole.Card -> ContainerStyle(c.surface, border = null, raised = true, dark = EchoMarkDesign.isDark)
            // くぼみは影だけに頼らず、地を一段濃くして形が分かるようにする(影の出方は端末によって弱いことがある)
            SurfaceRole.Nested, SurfaceRole.Selected ->
                ContainerStyle(c.surfaceContainer, border = null, inset = true, dark = EchoMarkDesign.isDark)
        }
    }
}

/**
 * 入れ物の外側・内側の影(ソフトだけ)。[shape] は入れ物の形。
 * 入れ物の Modifier の先頭(切り抜きより前)に付けること。
 */
fun Modifier.containerDepth(style: ContainerStyle, shape: Shape): Modifier = this
    .then(if (style.raised) Modifier.softRaised(shape, style.dark) else Modifier)
    .then(if (style.inset) Modifier.softInset(shape, style.dark) else Modifier)

/**
 * 中立のカード。カードの地色・枠線・影はデザインに従う。
 * @param classic クラシックでの地色(これまでの色。既定は M3 の Card と同じ)
 * @param classicBorder クラシックでの枠線(これまで OutlinedCard だったもの)
 * @param border どのデザインでも付ける枠線(選んだものの強調など)。デザインの枠線より優先する
 * @param accentColor 意味のある色(警告・今のもの など)にするときの地色。どのデザインでもこの色にし、枠線・影は付けない
 * @param clickModifier 押したときの操作(長押し・選択など、[onClick] で表せないもの)。影の後・形での切り抜きの後に付けるので、
 *   波紋はカードの形に収まり、ソフトの影も欠けない。呼び出し側の [modifier] で切り抜かないこと
 */
@Composable
fun DesignCard(
    modifier: Modifier = Modifier,
    role: SurfaceRole = SurfaceRole.Card,
    classic: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    accentColor: Color? = null,
    clickModifier: Modifier? = null,
    classicBorder: BorderStroke? = null,
    border: BorderStroke? = null,
    shape: Shape = CardDefaults.shape,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val style = if (accentColor != null) ContainerStyle(accentColor, border = null) else containerStyle(role, classic)
    val resolvedBorder = when {
        border != null -> border
        accentColor != null -> null
        EchoMarkDesign.style == DesignStyle.CLASSIC -> classicBorder
        else -> style.border
    }
    val colors = CardDefaults.cardColors(containerColor = style.color, contentColor = contentColor)
    val outer = modifier
        .containerDepth(style, shape)
        .then(if (clickModifier != null) Modifier.clip(shape).then(clickModifier) else Modifier)
    if (onClick != null) {
        Card(onClick = onClick, modifier = outer, enabled = enabled, shape = shape, colors = colors, border = resolvedBorder, content = content)
    } else {
        Card(modifier = outer, shape = shape, colors = colors, border = resolvedBorder, content = content)
    }
}

/**
 * 意味のある色のカード(強調・選んだもの・警告など)。色はどのデザインでも同じ役割の色を使い、形だけデザインに従う。
 * ソフトでも押し出さない(色の付いた面は、色で区切れるため)。
 */
@Composable
fun AccentCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    contentColor: Color = contentColorFor(containerColor),
    border: BorderStroke? = null,
    shape: Shape = CardDefaults.shape,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

/**
 * 中立の入れ物(パネル・枠)。[DesignCard] と同じ描き方で、中身の並べ方を自由にしたいときに使う。
 * @param classic クラシックでの地色(これまでの色)
 */
@Composable
fun DesignSurface(
    classic: Color,
    modifier: Modifier = Modifier,
    role: SurfaceRole = SurfaceRole.Card,
    shape: Shape = MaterialTheme.shapes.medium,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val style = containerStyle(role, classic)
    val outer = modifier.containerDepth(style, shape)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = outer,
            enabled = enabled,
            shape = shape,
            color = style.color,
            contentColor = contentColor,
            border = border ?: style.border,
            content = content
        )
    } else {
        Surface(
            modifier = outer,
            shape = shape,
            color = style.color,
            contentColor = contentColor,
            border = border ?: style.border,
            content = content
        )
    }
}
