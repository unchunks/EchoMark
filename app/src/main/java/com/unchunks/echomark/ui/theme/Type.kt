package com.unchunks.echomark.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// M3 の既定値をベースに、日本語(漢字・かな)が詰まって見えないよう行間を広げ、
// 欧文向けの字間(letterSpacing)を控えめにする。
private val BaseTypography = Typography()

// 行間を広げても1行目の上に余白が偏らないよう、上下均等に配置する
private val JapaneseLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun TextStyle.ja(lineHeight: Int, letterSpacing: Double = 0.0): TextStyle = copy(
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = JapaneseLineHeightStyle
)

val Typography = Typography(
    displayLarge = BaseTypography.displayLarge.ja(lineHeight = 68),
    displayMedium = BaseTypography.displayMedium.ja(lineHeight = 56),
    displaySmall = BaseTypography.displaySmall.ja(lineHeight = 48),
    headlineLarge = BaseTypography.headlineLarge.ja(lineHeight = 44),
    headlineMedium = BaseTypography.headlineMedium.ja(lineHeight = 40),
    headlineSmall = BaseTypography.headlineSmall.ja(lineHeight = 34),
    titleLarge = BaseTypography.titleLarge.ja(lineHeight = 30),
    titleMedium = BaseTypography.titleMedium.ja(lineHeight = 24, letterSpacing = 0.1),
    titleSmall = BaseTypography.titleSmall.ja(lineHeight = 20, letterSpacing = 0.1),
    // 本文は行間を文字サイズの 1.6 倍前後にして長文の要約でも読みやすくする
    bodyLarge = BaseTypography.bodyLarge.ja(lineHeight = 26, letterSpacing = 0.15),
    bodyMedium = BaseTypography.bodyMedium.ja(lineHeight = 22, letterSpacing = 0.1),
    bodySmall = BaseTypography.bodySmall.ja(lineHeight = 18, letterSpacing = 0.1),
    labelLarge = BaseTypography.labelLarge.ja(lineHeight = 20, letterSpacing = 0.1),
    labelMedium = BaseTypography.labelMedium.ja(lineHeight = 16, letterSpacing = 0.2),
    labelSmall = BaseTypography.labelSmall.ja(lineHeight = 16, letterSpacing = 0.2)
)
