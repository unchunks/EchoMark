package com.unchunks.echomark.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unchunks.echomark.domain.bookmark.model.ReadingBlock
import com.unchunks.echomark.domain.bookmark.model.ReadingBlocks
import com.unchunks.echomark.domain.bookmark.model.ReadingFont
import com.unchunks.echomark.domain.bookmark.model.ReadingFontSize
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.ReadingTheme
import com.unchunks.echomark.domain.bookmark.model.ReadingTime
import com.unchunks.echomark.ui.components.rememberHighlighted

/** 読書モードの本文の幅の上限。広い画面でも、1行が長くなりすぎない(読みやすい 40 文字前後)ようにする */
private val READING_MAX_WIDTH = 640.dp

/** 本文の行の高さの、文字の大きさに対する倍率 */
private const val LINE_HEIGHT_RATIO = 1.8f

/** 読書モードの背景と文字の色 */
private data class ReadingColors(val background: Color, val text: Color, val secondary: Color)

@Composable
private fun ReadingTheme.colors(): ReadingColors = when (this) {
    ReadingTheme.SYSTEM -> ReadingColors(
        MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant
    )
    ReadingTheme.PAPER -> ReadingColors(Color(0xFFF4ECD8), Color(0xFF3B3226), Color(0xFF6E6250))
    ReadingTheme.WHITE -> ReadingColors(Color(0xFFFFFFFF), Color(0xFF1B1B1B), Color(0xFF5F5F5F))
    ReadingTheme.DARK -> ReadingColors(Color(0xFF121212), Color(0xFFD6D6D6), Color(0xFF9A9A9A))
}

/**
 * 読書モード(#59)。本文だけを、背景・文字の大きさ・書体を選べる読みやすい形で全画面に出す。
 * タイトル・出どころ・読む時間の目安のあとに、本文を段落ごとに並べる。読み進めた割合を上の細い線で示し、読んでいる間は画面を消さない。
 *
 * @param blocks [ReadingBlocks.from] で分けた本文
 * @param highlightTerms 検索結果から開いたときの検索語。本文で一致した文字を強調する
 * @param onStyleChange 文字の大きさ・背景・書体を変えた(端末に保存される)
 * @param onClose 読書モードを閉じて、詳細画面に戻る
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingModeContent(
    title: String,
    source: String,
    readingMinutes: Int?,
    blocks: List<ReadingBlock>,
    style: ReadingStyle,
    highlightTerms: List<String>,
    onStyleChange: (ReadingStyle) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = style.theme.colors()
    val listState = rememberLazyListState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // 見出し(1つ目)と終わりの印(最後)を含めた数で、どこまで読み進めたかを出す
    val progress by remember(blocks.size) {
        derivedStateOf { ReadingBlocks.progress(listState.firstVisibleItemIndex, blocks.size + 2) }
    }
    val family = if (style.font == ReadingFont.SERIF) FontFamily.Serif else FontFamily.Default

    BackHandler(onBack = onClose)
    // 読んでいる間は画面を消さない(読書モードを閉じたら元に戻す)
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.text,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("読書モード", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, contentDescription = "読書モードを閉じる")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.TextFields, contentDescription = "文字の大きさ・背景・書体")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.secondary,
                        navigationIconContentColor = colors.text,
                        actionIconContentColor = colors.text
                    )
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "読み進めた割合 ${(progress * 100).toInt()}%" },
                    color = colors.secondary,
                    trackColor = colors.background,
                    // 細い線の右端に出る丸い印は、読み進めた割合には要らない
                    drawStopIndicator = {}
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = READING_MAX_WIDTH).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 48.dp
                )
            ) {
                item(key = "header") {
                    Column(Modifier.padding(bottom = 20.dp)) {
                        Text(
                            text = rememberHighlighted(title, highlightTerms),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = family,
                            fontWeight = FontWeight.Bold,
                            color = colors.text,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = listOfNotNull(source.takeIf { it.isNotBlank() }, readingMinutes?.let { "${ReadingTime.label(it)}で読めます" })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.secondary
                        )
                    }
                }
                itemsIndexed(blocks, key = { index, _ -> "block_$index" }) { _, block ->
                    when (block) {
                        is ReadingBlock.Heading -> Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = family,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondary,
                            modifier = Modifier
                                .padding(top = 20.dp, bottom = 8.dp)
                                .semantics { heading() }
                        )
                        is ReadingBlock.Paragraph -> Text(
                            text = rememberHighlighted(block.text, highlightTerms),
                            fontFamily = family,
                            fontSize = style.fontSize.sp.sp,
                            lineHeight = (style.fontSize.sp * LINE_HEIGHT_RATIO).sp,
                            color = colors.text,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }
                }
                item(key = "end") {
                    Text(
                        text = "おわり",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    if (showSettings) {
        ReadingStyleSheet(style = style, onStyleChange = onStyleChange, onDismiss = { showSettings = false })
    }
}

/** 文字の大きさ・書体・背景を選ぶシート。選ぶとすぐ本文に反映され、端末に保存される。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingStyleSheet(
    style: ReadingStyle,
    onStyleChange: (ReadingStyle) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            SheetTitle("文字の大きさ")
            ChipRow {
                ReadingFontSize.entries.forEach { size ->
                    ChoiceChip(size.label(), selected = style.fontSize == size) { onStyleChange(style.copy(fontSize = size)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            SheetTitle("書体")
            ChipRow {
                ReadingFont.entries.forEach { font ->
                    ChoiceChip(font.label(), selected = style.font == font) { onStyleChange(style.copy(font = font)) }
                }
            }
            Spacer(Modifier.height(20.dp))
            SheetTitle("背景")
            ChipRow {
                ReadingTheme.entries.forEach { theme ->
                    ChoiceChip(theme.label(), selected = style.theme == theme) { onStyleChange(style.copy(theme = theme)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        }
    )
}

internal fun ReadingFontSize.label(): String = when (this) {
    ReadingFontSize.SMALL -> "小"
    ReadingFontSize.MEDIUM -> "中"
    ReadingFontSize.LARGE -> "大"
    ReadingFontSize.XLARGE -> "特大"
}

internal fun ReadingFont.label(): String = when (this) {
    ReadingFont.SANS -> "ゴシック体"
    ReadingFont.SERIF -> "明朝体"
}

internal fun ReadingTheme.label(): String = when (this) {
    ReadingTheme.SYSTEM -> "端末に合わせる"
    ReadingTheme.PAPER -> "紙"
    ReadingTheme.WHITE -> "白"
    ReadingTheme.DARK -> "黒"
}
