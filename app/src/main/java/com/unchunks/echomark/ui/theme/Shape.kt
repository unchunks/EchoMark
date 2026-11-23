package com.unchunks.echomark.ui.theme

import androidx.compose.ui.unit.dp

/**
 * クラシックの角丸の段階。ほかのデザインの角丸([DesignRadii.scale])は、この段階を基準に読み替える。
 * - extraSmall 4dp: タグなどの小さな要素・入力欄
 * - small 8dp: チップ・サムネイル
 * - medium 16dp: カード(やや丸めにして柔らかい印象にする)
 * - large 20dp: 大きめのパネル
 * - extraLarge 28dp: ダイアログ・ボトムシート
 */
internal val ClassicRadii = DesignRadii(extraSmall = 4.dp, small = 8.dp, medium = 16.dp, large = 20.dp, extraLarge = 28.dp)

// 角丸は M3 の段階に合わせる
val Shapes = ClassicRadii.toShapes()
