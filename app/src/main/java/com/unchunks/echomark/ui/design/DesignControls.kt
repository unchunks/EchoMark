package com.unchunks.echomark.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.CapsuleShape
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.softInset
import com.unchunks.echomark.ui.theme.softRaised
import kotlin.math.roundToInt

// ボタン・浮いたボタン・チップ・スイッチをデザインごとに描き分ける。
// 大きさ・押せる範囲・並び方は変えず、形・色・影だけを変える(操作の覚え方はどのデザインでも同じにする)。
//
// | 部品           | ペーパー                  | クリア(iPhone 風)                     | ソフト(ニューモフィズム)          |
// | ボタンの形     | 角の小さい四角(活字の札) | 両端の丸い形(M3 と同じ)               | 両端の丸い形を押し出す               |
// | 色付きボタン   | M3 と同じ                 | 薄い青の地に青い文字                   | 地と同じ色を押し出し、青い文字       |
// | 枠線ボタン     | 墨色の枠                  | 枠なしの灰色の地に青い文字             | 地と同じ色を押し出す(枠なし)       |
// | 追加ボタン     | M3 と同じ                 | 青の地に白い文字のカプセル             | 地と同じ色を押し出し、青い文字       |
// | 絞り込みチップ | 墨の枠 / 選ぶと墨で塗る   | カプセル。灰色 / 選ぶと青で塗る        | 押し出し / 選ぶとくぼむ              |
// | スイッチ       | M3 と同じ                 | オンは緑                               | M3 と同じ                            |
// エコーは M3 Expressive に合わせ、チップを枠なしのトーナルな面にする。それ以外は M3 の既定のまま。

/** ボタンの形。ペーパーだけ角の小さい四角にする */
@Composable
@ReadOnlyComposable
fun designButtonShape(): Shape =
    if (LocalDesignStyle.current == DesignStyle.PAPER) MaterialTheme.shapes.small else CircleShape

/** ソフトの押し出し。押せないときは押し出さない(平らにして、押せないことを伝える) */
private fun Modifier.softButtonDepth(style: DesignStyle, enabled: Boolean, shape: Shape, dark: Boolean): Modifier =
    if (style == DesignStyle.SOFT && enabled) softRaised(shape, dark, depth = 4.dp) else this

/**
 * 影(ソフトの光と影)を、部品の見た目の大きさに合わせて付ける。[content] には見た目に付ける Modifier を渡す。
 *
 * M3 のボタン・チップ・小さな浮いたボタンは、押せる範囲を 48dp に広げるために外側の大きさを見た目
 * (チップ 32dp・ボタン 40dp)より大きくする。そのまま影を付けると、影が見た目からはみ出してずれる。
 * そこで M3 の広げ方を止め、外側で同じだけ場所を取って中身を中央に置き([minimumTouchSpace])、影は中身(見た目)に付ける。
 * 押せる範囲は、Compose が小さな部品の当たり判定を 48dp まで広げるので変わらない。
 * 影が無いとき(ソフト以外・押せないとき)は、M3 の部品をそのまま置く。
 * @param modifier 見た目に付ける影。影が無いときは [Modifier] そのもの
 */
@Composable
private fun WithVisualDepth(modifier: Modifier, content: @Composable (visual: Modifier) -> Unit) {
    if (modifier == Modifier) {
        content(Modifier)
    } else {
        val minimum = LocalMinimumInteractiveComponentSize.current
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            content(Modifier.minimumTouchSpace(minimum).then(modifier))
        }
    }
}

/**
 * M3 の minimumInteractiveComponentSize と同じ場所の取り方。中身は受け取った制約のまま測り
 * (幅いっぱい・weight の指定もそのまま効く)、外側の大きさだけを [size] 以上にして、中身を中央に置く。
 */
private fun Modifier.minimumTouchSpace(size: Dp): Modifier =
    if (!size.isSpecified || size <= 0.dp) {
        this
    } else {
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val minimum = size.roundToPx()
            val width = maxOf(placeable.width, minimum)
            val height = maxOf(placeable.height, minimum)
            layout(width, height) {
                placeable.place(
                    ((width - placeable.width) / 2f).roundToInt(),
                    ((height - placeable.height) / 2f).roundToInt()
                )
            }
        }
    }

/**
 * いちばん強いボタン([Button])。色は呼び出し側が決め(削除などの警告色を含む)、形と影はデザインに従う。
 */
@Composable
fun EchoMarkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val style = LocalDesignStyle.current
    val shape = designButtonShape()
    WithVisualDepth(Modifier.softButtonDepth(style, enabled, shape, EchoMarkDesign.isDark)) { visual ->
        Button(
            onClick = onClick,
            modifier = modifier.then(visual),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            content = content
        )
    }
}

/** 色付きのボタン([FilledTonalButton])。色・形・影はデザインに従う */
@Composable
fun EchoMarkTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val style = LocalDesignStyle.current
    val c = MaterialTheme.colorScheme
    val shape = designButtonShape()
    val colors = when (style) {
        DesignStyle.CLEAR -> ButtonDefaults.filledTonalButtonColors(containerColor = c.primaryContainer, contentColor = c.primary)
        DesignStyle.SOFT -> ButtonDefaults.filledTonalButtonColors(containerColor = c.surface, contentColor = c.primary)
        else -> ButtonDefaults.filledTonalButtonColors()
    }
    WithVisualDepth(Modifier.softButtonDepth(style, enabled, shape, EchoMarkDesign.isDark)) { visual ->
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.then(visual),
            enabled = enabled,
            shape = shape,
            colors = colors,
            contentPadding = contentPadding,
            content = content
        )
    }
}

/**
 * 控えめなボタン([OutlinedButton])。形・枠・地はデザインに従う。
 * @param contentColor 文字の色(警告など)。null ならデザインの色
 */
@Composable
fun EchoMarkOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val style = LocalDesignStyle.current
    val c = MaterialTheme.colorScheme
    val shape = designButtonShape()
    val colors = when (style) {
        DesignStyle.CLEAR -> ButtonDefaults.outlinedButtonColors(containerColor = c.surfaceContainerHigh, contentColor = contentColor ?: c.primary)
        DesignStyle.SOFT -> ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = contentColor ?: c.onSurface)
        // ペーパーは文字をアクセント(黄土色)にし、墨色の枠と組み合わせる
        DesignStyle.PAPER -> ButtonDefaults.outlinedButtonColors(contentColor = contentColor ?: c.primary)
        // クラシック・エコーは M3 の既定の色のまま(文字は onSurfaceVariant)
        DesignStyle.CLASSIC, DesignStyle.ECHO ->
            if (contentColor != null) ButtonDefaults.outlinedButtonColors(contentColor = contentColor) else ButtonDefaults.outlinedButtonColors()
    }
    // クリアとソフトは枠線を使わない(地の色と影で形を示す)。ペーパーは墨色の枠
    val border: BorderStroke? = when (style) {
        DesignStyle.CLEAR, DesignStyle.SOFT -> null
        DesignStyle.PAPER -> BorderStroke(1.dp, if (enabled) c.outline else c.onSurface.copy(alpha = 0.12f))
        else -> ButtonDefaults.outlinedButtonBorder(enabled)
    }
    WithVisualDepth(Modifier.softButtonDepth(style, enabled, shape, EchoMarkDesign.isDark)) { visual ->
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.then(visual),
            enabled = enabled,
            shape = shape,
            colors = colors,
            border = border,
            contentPadding = contentPadding,
            content = content
        )
    }
}

/** 浮いたボタンの色と形 */
@Immutable
private data class FabStyle(val shape: Shape?, val container: Color, val content: Color, val flat: Boolean)

@Composable
private fun fabStyle(container: Color?, content: Color?): FabStyle {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        // iPhone の目立たせたいボタン: 青の地に白い文字のカプセル
        DesignStyle.CLEAR -> FabStyle(CapsuleShape, c.primary, c.onPrimary, flat = false)
        // 地と同じ色を押し出す(影は Material の影ではなく光と影で描く)
        DesignStyle.SOFT -> FabStyle(null, c.surface, c.primary, flat = true)
        else -> {
            val resolved = container ?: FloatingActionButtonDefaults.containerColor
            FabStyle(null, resolved, content ?: contentColorFor(resolved), flat = false)
        }
    }
}

/** 画面の主な操作の浮いたボタン([ExtendedFloatingActionButton])。色・形・影はデザインに従う */
@Composable
fun EchoMarkExtendedFab(
    text: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true
) {
    val fab = fabStyle(container = null, content = null)
    val shape = fab.shape ?: FloatingActionButtonDefaults.extendedFabShape
    ExtendedFloatingActionButton(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = if (fab.flat) modifier.softRaised(shape, EchoMarkDesign.isDark, depth = 6.dp) else modifier,
        expanded = expanded,
        shape = shape,
        containerColor = fab.container,
        contentColor = fab.content,
        elevation = if (fab.flat) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else FloatingActionButtonDefaults.elevation()
    )
}

/**
 * 小さな浮いたボタン([SmallFloatingActionButton])。
 * @param containerColor クラシック・エコー・ペーパーでの地色(null なら M3 の既定)
 */
@Composable
fun EchoMarkSmallFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
    content: @Composable () -> Unit
) {
    val style = LocalDesignStyle.current
    val c = MaterialTheme.colorScheme
    val fab = when (style) {
        // 小さな浮いたボタンは、主な操作ほど目立たせない(白い丸に青いアイコン)
        DesignStyle.CLEAR -> FabStyle(CircleShape, c.surfaceContainerLow, c.primary, flat = false)
        else -> fabStyle(containerColor, contentColor)
    }
    val shape = fab.shape ?: FloatingActionButtonDefaults.smallShape
    WithVisualDepth(if (fab.flat) Modifier.softRaised(shape, EchoMarkDesign.isDark, depth = 4.dp) else Modifier) { visual ->
        SmallFloatingActionButton(
            onClick = onClick,
            modifier = modifier.then(visual),
            shape = shape,
            containerColor = fab.container,
            contentColor = fab.content,
            elevation = if (fab.flat) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else FloatingActionButtonDefaults.elevation(),
            content = content
        )
    }
}

/** チップの見た目(色・枠・形・影) */
private class ChipLook(
    val shape: Shape,
    val colors: SelectableChipColors,
    val border: BorderStroke?,
    val depth: Modifier
)

@Composable
private fun selectableChipLook(selected: Boolean, enabled: Boolean, input: Boolean): ChipLook {
    val style = LocalDesignStyle.current
    val c = MaterialTheme.colorScheme
    val dark = EchoMarkDesign.isDark
    val defaultShape = if (input) InputChipDefaults.shape else FilterChipDefaults.shape
    @Composable
    fun defaultColors(
        containerColor: Color = Color.Transparent,
        labelColor: Color = c.onSurfaceVariant,
        iconColor: Color = c.onSurfaceVariant,
        selectedContainerColor: Color = c.secondaryContainer,
        selectedContentColor: Color = c.onSecondaryContainer
    ): SelectableChipColors = if (input) {
        InputChipDefaults.inputChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            leadingIconColor = iconColor,
            trailingIconColor = iconColor,
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedContentColor,
            selectedLeadingIconColor = selectedContentColor,
            selectedTrailingIconColor = selectedContentColor
        )
    } else {
        FilterChipDefaults.filterChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            iconColor = iconColor,
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedContentColor,
            selectedLeadingIconColor = selectedContentColor,
            selectedTrailingIconColor = selectedContentColor
        )
    }
    return when (style) {
        DesignStyle.CLASSIC -> ChipLook(
            shape = defaultShape,
            colors = if (input) InputChipDefaults.inputChipColors() else FilterChipDefaults.filterChipColors(),
            border = if (input) InputChipDefaults.inputChipBorder(enabled, selected) else FilterChipDefaults.filterChipBorder(enabled, selected),
            depth = Modifier
        )
        // 枠なしのトーナルな面。選ぶと一段強い面になる
        DesignStyle.ECHO -> ChipLook(defaultShape, defaultColors(containerColor = c.surfaceContainerHigh), border = null, depth = Modifier)
        // 墨の枠。選ぶと墨で塗る(判を押したように)
        DesignStyle.PAPER -> ChipLook(
            shape = defaultShape,
            colors = defaultColors(selectedContainerColor = c.primary, selectedContentColor = c.onPrimary),
            border = if (selected) null else BorderStroke(1.dp, if (enabled) c.outline else c.onSurface.copy(alpha = 0.12f)),
            depth = Modifier
        )
        // カプセル。灰色の地、選ぶと青で塗る
        DesignStyle.CLEAR -> ChipLook(
            shape = CapsuleShape,
            colors = defaultColors(
                containerColor = c.surfaceContainerHigh,
                selectedContainerColor = c.primary,
                selectedContentColor = c.onPrimary
            ),
            border = null,
            depth = Modifier
        )
        // 押し出したチップ。選ぶとくぼみ、文字が青くなる
        DesignStyle.SOFT -> ChipLook(
            shape = defaultShape,
            colors = defaultColors(
                containerColor = c.surface,
                // 選んだものは一段濃い地のくぼみ(影の出方が弱い端末でも見分けられる)
                selectedContainerColor = c.surfaceContainer,
                selectedContentColor = c.primary
            ),
            border = null,
            depth = when {
                !enabled -> Modifier
                selected -> Modifier.softInset(defaultShape, dark, depth = 3.dp)
                else -> Modifier.softRaised(defaultShape, dark, depth = 3.dp)
            }
        )
    }
}

/** 絞り込みのチップ([FilterChip])。色・枠・形・影はデザインに従う */
@Composable
fun EchoMarkFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val look = selectableChipLook(selected, enabled, input = false)
    WithVisualDepth(look.depth) { visual ->
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier.then(visual),
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            shape = look.shape,
            colors = look.colors,
            border = look.border
        )
    }
}

/** 入力した値のチップ([InputChip])。色・枠・形・影はデザインに従う */
@Composable
fun EchoMarkInputChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val look = selectableChipLook(selected, enabled, input = true)
    WithVisualDepth(look.depth) { visual ->
        InputChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier.then(visual),
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            shape = look.shape,
            colors = look.colors,
            border = look.border
        )
    }
}

/** 選ばない(押すだけの)チップの見た目。絞り込みのチップの「選んでいない」ときにそろえる */
private class ActionChipLook(val shape: Shape, val colors: ChipColors?, val border: BorderStroke?, val depth: Modifier)

@Composable
private fun actionChipLook(enabled: Boolean, defaultShape: Shape, defaultBorder: BorderStroke?): ActionChipLook {
    val style = LocalDesignStyle.current
    val c = MaterialTheme.colorScheme
    val dark = EchoMarkDesign.isDark
    return when (style) {
        DesignStyle.CLASSIC -> ActionChipLook(defaultShape, colors = null, border = defaultBorder, depth = Modifier)
        DesignStyle.ECHO -> ActionChipLook(defaultShape, actionChipColors(c.surfaceContainerHigh), border = null, depth = Modifier)
        DesignStyle.PAPER -> ActionChipLook(
            defaultShape,
            colors = null,
            border = BorderStroke(1.dp, if (enabled) c.outline else c.onSurface.copy(alpha = 0.12f)),
            depth = Modifier
        )
        DesignStyle.CLEAR -> ActionChipLook(CapsuleShape, actionChipColors(c.surfaceContainerHigh), border = null, depth = Modifier)
        DesignStyle.SOFT -> ActionChipLook(
            defaultShape,
            actionChipColors(c.surface),
            border = null,
            depth = if (enabled) Modifier.softRaised(defaultShape, dark, depth = 3.dp) else Modifier
        )
    }
}

@Composable
private fun actionChipColors(container: Color): ChipColors =
    AssistChipDefaults.assistChipColors(containerColor = container)

/** 操作のチップ([AssistChip])。色・枠・形・影はデザインに従う */
@Composable
fun EchoMarkAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val look = actionChipLook(enabled, AssistChipDefaults.shape, AssistChipDefaults.assistChipBorder(enabled))
    WithVisualDepth(look.depth) { visual ->
        AssistChip(
            onClick = onClick,
            label = label,
            modifier = modifier.then(visual),
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            shape = look.shape,
            colors = look.colors ?: AssistChipDefaults.assistChipColors(),
            border = look.border
        )
    }
}

/** 候補のチップ([SuggestionChip])。色・枠・形・影はデザインに従う */
@Composable
fun EchoMarkSuggestionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null
) {
    val look = actionChipLook(enabled, SuggestionChipDefaults.shape, SuggestionChipDefaults.suggestionChipBorder(enabled))
    WithVisualDepth(look.depth) { visual ->
        SuggestionChip(
            onClick = onClick,
            label = label,
            modifier = modifier.then(visual),
            enabled = enabled,
            icon = icon,
            shape = look.shape,
            colors = look.colors ?: SuggestionChipDefaults.suggestionChipColors(),
            border = look.border
        )
    }
}

/** スイッチ([Switch])。クリアだけ、オンを緑にする(iPhone のスイッチ) */
@Composable
fun EchoMarkSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbContent: (@Composable () -> Unit)? = null
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = thumbContent,
        enabled = enabled,
        colors = designSwitchColors()
    )
}

@Composable
private fun designSwitchColors(): SwitchColors {
    val c = MaterialTheme.colorScheme
    return if (LocalDesignStyle.current == DesignStyle.CLEAR) {
        SwitchDefaults.colors(
            checkedTrackColor = c.tertiary,
            checkedThumbColor = c.onTertiary,
            checkedIconColor = c.tertiary,
            checkedBorderColor = Color.Transparent
        )
    } else {
        SwitchDefaults.colors()
    }
}

/**
 * 切り替えボタン([androidx.compose.material3.SegmentedButton])の形。ペーパーだけ角の小さい四角を並べる。
 * 各ボタンの shape に渡す。
 */
@Composable
fun designSegmentShape(index: Int, count: Int): Shape =
    if (LocalDesignStyle.current == DesignStyle.PAPER) {
        SegmentedButtonDefaults.itemShape(index, count, baseShape = MaterialTheme.shapes.small)
    } else {
        SegmentedButtonDefaults.itemShape(index, count)
    }

/**
 * 切り替えボタンの色。各ボタンの colors に渡す。
 * - ペーパー: 選んだものを墨で塗る(絞り込みのチップと同じ)
 * - クリア: 灰色の地に、選んだものを白く抜く(iPhone の切り替えボタン)
 * - ソフト: 選んだものを押し込んだように一段濃くし、文字を青くする
 */
@Composable
fun designSegmentColors(): SegmentedButtonColors {
    val c = MaterialTheme.colorScheme
    val defaults = SegmentedButtonDefaults
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC, DesignStyle.ECHO -> defaults.colors()
        DesignStyle.PAPER -> defaults.colors(
            activeContainerColor = c.primary,
            activeContentColor = c.onPrimary,
            activeBorderColor = c.primary,
            inactiveBorderColor = c.outline
        )
        DesignStyle.CLEAR -> defaults.colors(
            activeContainerColor = c.surfaceContainerLow,
            activeContentColor = c.onSurface,
            activeBorderColor = c.outlineVariant,
            inactiveContainerColor = c.surfaceContainerHigh,
            inactiveContentColor = c.onSurface,
            inactiveBorderColor = c.outlineVariant
        )
        DesignStyle.SOFT -> defaults.colors(
            activeContainerColor = c.surfaceContainerHighest,
            activeContentColor = c.primary,
            inactiveContainerColor = c.surface
        )
    }
}
