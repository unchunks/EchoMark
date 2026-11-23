package com.unchunks.echomark.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unchunks.echomark.domain.repository.DesignStyle

// デザイン([DesignStyle])ごとの配色・文字・角丸。
// 画面は MaterialTheme のロールだけを参照し、デザインの違いはここと [EchoMarkDesign] に集める。

/** 今のデザイン。トークンだけで表せない部品(カードの枠線など)が参照する。 */
val LocalDesignStyle = staticCompositionLocalOf { DesignStyle.CLASSIC }

/** トークンだけで表せない、部品ごとのデザインの違い。 */
object EchoMarkDesign {
    val style: DesignStyle
        @Composable @ReadOnlyComposable get() = LocalDesignStyle.current

    /** ブックマークのカードの地色。ペーパーはカードを地に溶かし、枠線で区切る */
    val cardContainerColor: Color
        @Composable @ReadOnlyComposable get() = when (style) {
            DesignStyle.CLASSIC -> MaterialTheme.colorScheme.surfaceContainerLow
            DesignStyle.ECHO -> MaterialTheme.colorScheme.surfaceContainer
            DesignStyle.PAPER -> MaterialTheme.colorScheme.surface
        }

    /** カードの枠線。ペーパーだけ罫線のような細い線を引く */
    val cardBorder: BorderStroke?
        @Composable @ReadOnlyComposable get() =
            if (style == DesignStyle.PAPER) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
}

/** [style] の配色。ダイナミックカラーを使わないとき。 */
internal fun designColorScheme(style: DesignStyle, dark: Boolean): ColorScheme = when (style) {
    DesignStyle.CLASSIC -> if (dark) DarkColorScheme else LightColorScheme
    DesignStyle.ECHO -> if (dark) EchoDarkColorScheme else EchoLightColorScheme
    DesignStyle.PAPER -> if (dark) PaperDarkColorScheme else PaperLightColorScheme
}

internal fun designTypography(style: DesignStyle): Typography = when (style) {
    DesignStyle.CLASSIC -> Typography
    DesignStyle.ECHO -> EchoTypography
    DesignStyle.PAPER -> PaperTypography
}

internal fun designShapes(style: DesignStyle): Shapes = when (style) {
    DesignStyle.CLASSIC -> Shapes
    DesignStyle.ECHO -> EchoShapes
    DesignStyle.PAPER -> PaperShapes
}

// ---- エコー: Material 3 Expressive の考え方(大きな見出し・丸い形・トーナルな強調)を配色・文字・角丸で表す ----
// ブランドのインディゴとティールの彩度を一段上げ、地色にわずかにインディゴを混ぜる。
// カード(surfaceContainer)と再発見の帯(primaryContainer)が地から浮いて見えるよう、コンテナの段差を広げる。

internal val EchoLightColorScheme = LightColorScheme.copy(
    primary = Color(0xFF3B47C2),
    primaryContainer = Color(0xFFDCDEFF),
    onPrimaryContainer = Color(0xFF1F2A97),
    inversePrimary = Color(0xFFBCC2FF),
    secondaryContainer = Color(0xFFDDDFFB),
    tertiary = Color(0xFF006A60),
    tertiaryContainer = Color(0xFF8DF5E6),
    onTertiaryContainer = Color(0xFF004F47),
    surface = Color(0xFFF8F6FF),
    background = Color(0xFFF8F6FF),
    surfaceBright = Color(0xFFF8F6FF),
    surfaceDim = Color(0xFFD9D7E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F0FC),
    surfaceContainer = Color(0xFFECEAF8),
    surfaceContainerHigh = Color(0xFFE6E4F3),
    surfaceContainerHighest = Color(0xFFE0DEEE),
    surfaceTint = Color(0xFF3B47C2)
)

internal val EchoDarkColorScheme = DarkColorScheme.copy(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF101D86),
    primaryContainer = Color(0xFF2C38AE),
    onPrimaryContainer = Color(0xFFE0E0FF),
    inversePrimary = Color(0xFF3B47C2),
    tertiary = Color(0xFF6FDACB),
    tertiaryContainer = Color(0xFF00504A),
    onTertiaryContainer = Color(0xFF8DF5E6),
    surface = Color(0xFF101118),
    background = Color(0xFF101118),
    surfaceDim = Color(0xFF101118),
    surfaceBright = Color(0xFF363844),
    surfaceContainerLowest = Color(0xFF0B0C12),
    surfaceContainerLow = Color(0xFF181A24),
    surfaceContainer = Color(0xFF1D1F2A),
    surfaceContainerHigh = Color(0xFF272936),
    surfaceContainerHighest = Color(0xFF323442),
    surfaceTint = Color(0xFFBCC2FF)
)

// 見出しを大きく太くして、画面の入口をはっきりさせる。本文は日本語向けの行間をそのまま使う
private fun TextStyle.emphasized(size: Int? = null, lineHeight: Int? = null): TextStyle = copy(
    fontWeight = FontWeight.SemiBold,
    fontSize = size?.sp ?: fontSize,
    lineHeight = lineHeight?.sp ?: this.lineHeight
)

internal val EchoTypography = Typography.copy(
    displayLarge = Typography.displayLarge.emphasized(),
    displayMedium = Typography.displayMedium.emphasized(),
    displaySmall = Typography.displaySmall.emphasized(),
    headlineLarge = Typography.headlineLarge.emphasized(size = 34, lineHeight = 46),
    headlineMedium = Typography.headlineMedium.emphasized(size = 30, lineHeight = 42),
    headlineSmall = Typography.headlineSmall.emphasized(size = 26, lineHeight = 36),
    titleLarge = Typography.titleLarge.emphasized(),
    titleMedium = Typography.titleMedium.emphasized(),
    titleSmall = Typography.titleSmall.emphasized()
)

// カードは 24dp、ダイアログやシートは 32dp 以上。丸みで柔らかく、要素の塊を大きく見せる
internal val EchoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** 再発見の帯の形。左下だけ角を小さくして、響き返ってくる吹き出しに見せる */
internal val EchoBubbleShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp)

// ---- ペーパー: 読書アプリ風 ----
// 紙色の地、墨色の文字、黄土色のアクセントだけに絞る。コンテナの段差は小さくし、カードより罫線で区切る。
// 文字色と地色のコントラスト比は、本文 12:1 以上、見出し(primary)5.5:1 以上。

internal val PaperLightColorScheme = LightColorScheme.copy(
    primary = Color(0xFF7A5A2E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0E2C8),
    onPrimaryContainer = Color(0xFF3E2A0E),
    inversePrimary = Color(0xFFE7C38E),
    secondary = Color(0xFF6B5F4E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE3D2),
    onSecondaryContainer = Color(0xFF3A3123),
    tertiary = Color(0xFF4E6352),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7E6D5),
    onTertiaryContainer = Color(0xFF223326),
    surface = Color(0xFFF7F3EA),
    onSurface = Color(0xFF2B2620),
    background = Color(0xFFF7F3EA),
    onBackground = Color(0xFF2B2620),
    surfaceVariant = Color(0xFFEAE2D3),
    onSurfaceVariant = Color(0xFF5E5547),
    surfaceTint = Color(0xFF7A5A2E),
    inverseSurface = Color(0xFF35302A),
    inverseOnSurface = Color(0xFFF7F0E4),
    outline = Color(0xFF8A7F6E),
    outlineVariant = Color(0xFFD9D0BF),
    surfaceBright = Color(0xFFF7F3EA),
    surfaceDim = Color(0xFFDDD6C8),
    surfaceContainerLowest = Color(0xFFFFFDF8),
    surfaceContainerLow = Color(0xFFF2EDE2),
    surfaceContainer = Color(0xFFEDE7DB),
    surfaceContainerHigh = Color(0xFFE7E1D4),
    surfaceContainerHighest = Color(0xFFE1DACC)
).withPaperFixedRoles()

internal val PaperDarkColorScheme = DarkColorScheme.copy(
    primary = Color(0xFFE7C38E),
    onPrimary = Color(0xFF412D07),
    primaryContainer = Color(0xFF5C431C),
    onPrimaryContainer = Color(0xFFFFDEAE),
    inversePrimary = Color(0xFF7A5A2E),
    secondary = Color(0xFFD6C6AE),
    onSecondary = Color(0xFF3A3020),
    secondaryContainer = Color(0xFF4F4535),
    onSecondaryContainer = Color(0xFFF3E2C9),
    tertiary = Color(0xFFB4CCB3),
    onTertiary = Color(0xFF203524),
    tertiaryContainer = Color(0xFF364B39),
    onTertiaryContainer = Color(0xFFD0E8CE),
    surface = Color(0xFF1A1815),
    onSurface = Color(0xFFEAE2D5),
    background = Color(0xFF1A1815),
    onBackground = Color(0xFFEAE2D5),
    surfaceVariant = Color(0xFF4A4337),
    onSurfaceVariant = Color(0xFFCFC5B4),
    surfaceTint = Color(0xFFE7C38E),
    inverseSurface = Color(0xFFEAE2D5),
    inverseOnSurface = Color(0xFF35302A),
    outline = Color(0xFF9A907F),
    outlineVariant = Color(0xFF4A4337),
    surfaceBright = Color(0xFF403C36),
    surfaceDim = Color(0xFF1A1815),
    surfaceContainerLowest = Color(0xFF14130F),
    surfaceContainerLow = Color(0xFF221F1B),
    surfaceContainer = Color(0xFF26231F),
    surfaceContainerHigh = Color(0xFF312D28),
    surfaceContainerHighest = Color(0xFF3C3832)
).withPaperFixedRoles()

/**
 * 固定ロール(primaryFixed など)はライト・ダーク共通の値にする([LightColorScheme] と同じ考え方)。
 * ライトのコンテナ色と、ダークのアクセント色を組み合わせる。
 */
private fun ColorScheme.withPaperFixedRoles(): ColorScheme = copy(
    primaryFixed = Color(0xFFF0E2C8),
    primaryFixedDim = Color(0xFFE7C38E),
    onPrimaryFixed = Color(0xFF412D07),
    onPrimaryFixedVariant = Color(0xFF3E2A0E),
    secondaryFixed = Color(0xFFEDE3D2),
    secondaryFixedDim = Color(0xFFD6C6AE),
    onSecondaryFixed = Color(0xFF3A3020),
    onSecondaryFixedVariant = Color(0xFF3A3123),
    tertiaryFixed = Color(0xFFD7E6D5),
    tertiaryFixedDim = Color(0xFFB4CCB3),
    onTertiaryFixed = Color(0xFF203524),
    onTertiaryFixedVariant = Color(0xFF223326)
)

// 見出し(display・headline・title)だけ明朝体にする。本文とラベルはゴシック体のまま、長文の読みやすさを保つ。
// 端末内の明朝体(Android の serif。日本語は Noto Serif CJK)を使うため、フォントは同梱しない
private fun TextStyle.serif(): TextStyle = copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal)

internal val PaperTypography = Typography.copy(
    displayLarge = Typography.displayLarge.serif(),
    displayMedium = Typography.displayMedium.serif(),
    displaySmall = Typography.displaySmall.serif(),
    headlineLarge = Typography.headlineLarge.serif(),
    headlineMedium = Typography.headlineMedium.serif(),
    headlineSmall = Typography.headlineSmall.serif(),
    titleLarge = Typography.titleLarge.serif(),
    titleMedium = Typography.titleMedium.serif()
)

// 角はほとんど丸めず、紙面の端正さを出す
internal val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)
