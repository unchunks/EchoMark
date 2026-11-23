package com.unchunks.echomark.ui.design

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkDesign
import com.unchunks.echomark.ui.theme.LocalDesignStyle

/**
 * いま画面より一段手前の面(ボトムシート)の上にいるか。
 * クリアのダークでは、手前の面を一段明るくし、その上のカードもさらに一段明るくする(iPhone の手前の面の考え方)。
 */
val LocalElevatedSurface = staticCompositionLocalOf { false }

/**
 * ボトムシートの地色。
 * - ペーパー・ソフト: 画面と同じ紙・地の色(中のカードは罫線・押し出しで区切る)
 * - クリア: ライトは画面と同じ淡い灰色(中の白いカードが浮く)、ダークは一段明るい灰色
 * - クラシック・エコー: M3 の既定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun designSheetContainerColor(): Color {
    val c = MaterialTheme.colorScheme
    return when (LocalDesignStyle.current) {
        DesignStyle.CLASSIC, DesignStyle.ECHO -> BottomSheetDefaults.ContainerColor
        DesignStyle.PAPER, DesignStyle.SOFT -> c.surface
        DesignStyle.CLEAR -> if (EchoMarkDesign.isDark) c.surfaceContainerLow else c.surface
    }
}

/** ボトムシート([ModalBottomSheet])。地色はデザインに従い、中のカードは手前の面の上として描く */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoMarkModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = designSheetContainerColor()
    ) {
        CompositionLocalProvider(LocalElevatedSurface provides true) { content() }
    }
}
