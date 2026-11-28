package com.unchunks.echomark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.unchunks.echomark.domain.repository.DesignStyle
import androidx.compose.ui.platform.LocalContext

internal val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = InversePrimaryLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = SurfaceLight, // 旧ロールを参照する部品向けの互換値
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceTint = PrimaryLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = ScrimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceDim = SurfaceDimLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    primaryFixed = PrimaryContainerLight,
    primaryFixedDim = PrimaryDark,
    onPrimaryFixed = OnPrimaryDark,
    onPrimaryFixedVariant = OnPrimaryContainerLight,
    secondaryFixed = SecondaryContainerLight,
    secondaryFixedDim = SecondaryDark,
    onSecondaryFixed = OnSecondaryDark,
    onSecondaryFixedVariant = OnSecondaryContainerLight,
    tertiaryFixed = TertiaryContainerLight,
    tertiaryFixedDim = TertiaryDark,
    onTertiaryFixed = OnTertiaryDark,
    onTertiaryFixedVariant = OnTertiaryContainerLight,
)

internal val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = InversePrimaryDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceTint = PrimaryDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = ScrimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceDim = SurfaceDimDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    primaryFixed = LightColorScheme.primaryFixed,
    primaryFixedDim = LightColorScheme.primaryFixedDim,
    onPrimaryFixed = LightColorScheme.onPrimaryFixed,
    onPrimaryFixedVariant = LightColorScheme.onPrimaryFixedVariant,
    secondaryFixed = LightColorScheme.secondaryFixed,
    secondaryFixedDim = LightColorScheme.secondaryFixedDim,
    onSecondaryFixed = LightColorScheme.onSecondaryFixed,
    onSecondaryFixedVariant = LightColorScheme.onSecondaryFixedVariant,
    tertiaryFixed = LightColorScheme.tertiaryFixed,
    tertiaryFixedDim = LightColorScheme.tertiaryFixedDim,
    onTertiaryFixed = LightColorScheme.onTertiaryFixed,
    onTertiaryFixedVariant = LightColorScheme.onTertiaryFixedVariant,
)

/**
 * アプリ共通のテーマ。
 * テーマモード・ダイナミックカラー・デザインは設定画面から切り替えられるよう、呼び出し側から引数で渡す。
 * ダイナミックカラーがオンのときは配色だけを壁紙に合わせ、文字と角丸は [designStyle] のものを使う。
 */
@Composable
fun EchoMarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12以降の壁紙に合わせた動的カラーリング設定(minSdk 31 のため常に利用可能)
    dynamicColor: Boolean = false,
    designStyle: DesignStyle = DesignStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> designColorScheme(designStyle, darkTheme)
    }
    // どのデザインでも同じ MaterialTheme の呼び出しで値だけを替え、設定からデザインを切り替えても
    // 画面の状態(ナビゲーションの履歴・スクロール位置)を保つ。
    // Expressive の動き(MotionScheme・MaterialExpressiveTheme)は material3 1.4 ではまだ公開されていないため使わない
    CompositionLocalProvider(LocalDesignStyle provides designStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = designShapes(designStyle),
            typography = designTypography(designStyle),
            content = content
        )
    }
}
