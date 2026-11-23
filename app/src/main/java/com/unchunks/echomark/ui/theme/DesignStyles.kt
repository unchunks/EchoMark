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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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

    /**
     * ブックマークのカードの地色。ペーパーとソフトはカードを地と同じ色にし、ペーパーは罫線、ソフトは光と影で区切る。
     * クリアは淡い灰色の地に白いカードを載せる(surfaceContainerLow をライトは白、ダークは一段明るい灰色にしてある)
     */
    val cardContainerColor: Color
        @Composable @ReadOnlyComposable get() = when (style) {
            DesignStyle.CLASSIC -> MaterialTheme.colorScheme.surfaceContainerLow
            DesignStyle.ECHO -> MaterialTheme.colorScheme.surfaceContainer
            DesignStyle.PAPER, DesignStyle.SOFT -> MaterialTheme.colorScheme.surface
            DesignStyle.CLEAR -> MaterialTheme.colorScheme.surfaceContainerLow
        }

    /** カードの枠線。ペーパーだけ罫線のような細い線を引く */
    val cardBorder: BorderStroke?
        @Composable @ReadOnlyComposable get() =
            if (style == DesignStyle.PAPER) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

    /** カードに付ける影。ソフトだけ、左上の光と右下の影で地から浮かせる(カードの形は shapes.medium) */
    val cardShadow: Modifier
        @Composable @ReadOnlyComposable get() =
            if (style == DesignStyle.SOFT) Modifier.softRaised(MaterialTheme.shapes.medium, isDark) else Modifier

    /** 今の配色が暗いか(ソフトの光と影の色を選ぶ。明暗の設定ではなく、実際の地色で決める) */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
}

/** [style] の配色。ダイナミックカラーを使わないとき。 */
internal fun designColorScheme(style: DesignStyle, dark: Boolean): ColorScheme = when (style) {
    DesignStyle.CLASSIC -> if (dark) DarkColorScheme else LightColorScheme
    DesignStyle.ECHO -> if (dark) EchoDarkColorScheme else EchoLightColorScheme
    DesignStyle.PAPER -> if (dark) PaperDarkColorScheme else PaperLightColorScheme
    DesignStyle.CLEAR -> if (dark) ClearDarkColorScheme else ClearLightColorScheme
    DesignStyle.SOFT -> if (dark) SoftDarkColorScheme else SoftLightColorScheme
}

internal fun designTypography(style: DesignStyle): Typography = when (style) {
    DesignStyle.CLASSIC -> Typography
    DesignStyle.ECHO -> EchoTypography
    DesignStyle.PAPER -> PaperTypography
    DesignStyle.CLEAR -> ClearTypography
    DesignStyle.SOFT -> SoftTypography
}

internal fun designShapes(style: DesignStyle): Shapes = when (style) {
    DesignStyle.CLASSIC -> Shapes
    DesignStyle.ECHO -> EchoShapes
    DesignStyle.PAPER -> PaperShapes
    DesignStyle.CLEAR -> ClearShapes
    DesignStyle.SOFT -> SoftShapes
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

// ---- クリア: iPhone 風(iOS の標準アプリのような見た目) ----
// 淡い灰色の地(ライト)・黒の地(ダーク)に、白(ダークは一段明るい灰色)の角丸のカードをまとめて載せる。
// アクセントは青1色。iOS の標準の青(#007AFF)は白地で 4.0:1 と本文の目安に届かないため、一段濃くして 4.5:1 以上にする。
// 書体は端末のもの(フォントは同梱しない)で、見出しを太くして大きな見出しの印象に寄せる。

internal val ClearLightColorScheme = LightColorScheme.copy(
    primary = Color(0xFF0062CC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E9FF),
    onPrimaryContainer = Color(0xFF00305F),
    inversePrimary = Color(0xFF5AA9FF),
    secondary = Color(0xFF5B5F66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF1B7A3A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2F2DA),
    onTertiaryContainer = Color(0xFF0B3B1A),
    error = Color(0xFFC4001A),
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF410006),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF5A5A61),
    surfaceTint = Color(0xFF0062CC),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD1D1D6),
    surfaceBright = Color(0xFFF2F2F7),
    surfaceDim = Color(0xFFDCDCE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEBEBF0),
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFDCDCE1)
)

internal val ClearDarkColorScheme = DarkColorScheme.copy(
    primary = Color(0xFF5AA9FF),
    onPrimary = Color(0xFF00305F),
    primaryContainer = Color(0xFF0A4585),
    onPrimaryContainer = Color(0xFFD9E9FF),
    inversePrimary = Color(0xFF0062CC),
    secondary = Color(0xFFC2C2C8),
    onSecondary = Color(0xFF2C2C2E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFF63D987),
    onTertiary = Color(0xFF003916),
    tertiaryContainer = Color(0xFF0F4D24),
    onTertiaryContainer = Color(0xFFC9F5D4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceTint = Color(0xFF5AA9FF),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF38383A),
    surfaceBright = Color(0xFF2C2C2E),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF242426),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C)
)

private fun TextStyle.weighted(weight: FontWeight): TextStyle = copy(fontWeight = weight)

internal val ClearTypography = Typography.copy(
    displayLarge = Typography.displayLarge.weighted(FontWeight.Bold),
    displayMedium = Typography.displayMedium.weighted(FontWeight.Bold),
    displaySmall = Typography.displaySmall.weighted(FontWeight.Bold),
    headlineLarge = Typography.headlineLarge.weighted(FontWeight.Bold),
    headlineMedium = Typography.headlineMedium.weighted(FontWeight.Bold),
    headlineSmall = Typography.headlineSmall.weighted(FontWeight.Bold),
    titleLarge = Typography.titleLarge.weighted(FontWeight.Bold),
    titleMedium = Typography.titleMedium.weighted(FontWeight.SemiBold),
    titleSmall = Typography.titleSmall.weighted(FontWeight.SemiBold)
)

// 一覧のまとまり(カード)は 12dp、シートやダイアログは 14〜20dp
internal val ClearShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

// ---- ソフト: ニューモフィズム ----
// 地色とカードを同じ色にし、左上の光(明るい影)と右下の影で面を浮かせる([softRaised])。
// ニューモフィズムは境目と文字が薄くなりやすいため、文字色は地色に対して 4.5:1 以上を守り、
// チップや入力欄などのコンテナには一段濃い色を残して、影が無くても形が分かるようにする。

internal val SoftLightColorScheme = LightColorScheme.copy(
    primary = Color(0xFF4651A3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6DAF3),
    onPrimaryContainer = Color(0xFF1E2664),
    inversePrimary = Color(0xFFB4BBFF),
    secondary = Color(0xFF59606D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7DCE4),
    onSecondaryContainer = Color(0xFF2B313C),
    tertiary = Color(0xFF2B7166),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCBE6E0),
    onTertiaryContainer = Color(0xFF0D3A33),
    background = Color(0xFFE4E8EE),
    onBackground = Color(0xFF2B313C),
    surface = Color(0xFFE4E8EE),
    onSurface = Color(0xFF2B313C),
    surfaceVariant = Color(0xFFD7DCE4),
    onSurfaceVariant = Color(0xFF4E5664),
    surfaceTint = Color(0xFF4651A3),
    outline = Color(0xFF7A8392),
    outlineVariant = Color(0xFFC6CCD6),
    surfaceBright = Color(0xFFECEFF3),
    surfaceDim = Color(0xFFD2D7DF),
    surfaceContainerLowest = Color(0xFFEEF1F5),
    surfaceContainerLow = Color(0xFFE8EBF1),
    surfaceContainer = Color(0xFFDFE3EA),
    surfaceContainerHigh = Color(0xFFD9DEE5),
    surfaceContainerHighest = Color(0xFFD2D7DF)
)

internal val SoftDarkColorScheme = DarkColorScheme.copy(
    primary = Color(0xFFB4BBFF),
    onPrimary = Color(0xFF1B226A),
    primaryContainer = Color(0xFF3A4387),
    onPrimaryContainer = Color(0xFFDDE0FF),
    inversePrimary = Color(0xFF4651A3),
    secondary = Color(0xFFBEC5D1),
    onSecondary = Color(0xFF29303A),
    secondaryContainer = Color(0xFF393E47),
    onSecondaryContainer = Color(0xFFE2E5EB),
    tertiary = Color(0xFF8DD3C6),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF1E5048),
    onTertiaryContainer = Color(0xFFC8F0E7),
    background = Color(0xFF2A2E35),
    onBackground = Color(0xFFE2E5EB),
    surface = Color(0xFF2A2E35),
    onSurface = Color(0xFFE2E5EB),
    surfaceVariant = Color(0xFF3A3F48),
    onSurfaceVariant = Color(0xFFB2B9C4),
    surfaceTint = Color(0xFFB4BBFF),
    outline = Color(0xFF8A919C),
    outlineVariant = Color(0xFF454A54),
    surfaceBright = Color(0xFF3A3F48),
    surfaceDim = Color(0xFF23272D),
    surfaceContainerLowest = Color(0xFF23272D),
    surfaceContainerLow = Color(0xFF2A2E35),
    surfaceContainer = Color(0xFF30343C),
    surfaceContainerHigh = Color(0xFF363A42),
    surfaceContainerHighest = Color(0xFF3D424B)
)

internal val SoftTypography = Typography.copy(
    titleLarge = Typography.titleLarge.weighted(FontWeight.Medium),
    titleMedium = Typography.titleMedium.weighted(FontWeight.Medium)
)

// 押し出した面が柔らかく見えるよう、全体に大きめに丸める
internal val SoftShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * ニューモフィズムの押し出した面。[shape] の外側に、左上へ明るい影(光)、右下へ暗い影を落とす。
 * 地色とこの面の色は同じにして使う。影は要素の外に描くので、親が切り抜く(clip)と欠ける。
 */
internal fun Modifier.softRaised(shape: Shape, dark: Boolean, depth: Dp = 5.dp): Modifier {
    val light = if (dark) Color(0x593F444D) else Color(0xE6FFFFFF)
    val shade = if (dark) Color(0x99121417) else Color(0x80A3AFC2)
    return this
        .dropShadow(shape, Shadow(radius = depth * 2, color = light, offset = DpOffset(-depth, -depth)))
        .dropShadow(shape, Shadow(radius = depth * 2, color = shade, offset = DpOffset(depth, depth)))
}
