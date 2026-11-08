package com.unchunks.echomark.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// 角丸は M3 の段階に合わせる。カード(medium)はやや丸めにして柔らかい印象にする
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), // タグなどの小さな要素
    small = RoundedCornerShape(8.dp), // チップ・サムネイル
    medium = RoundedCornerShape(16.dp), // カード
    large = RoundedCornerShape(20.dp), // ボトムシート・大きめのパネル
    extraLarge = RoundedCornerShape(28.dp) // ダイアログ
)
