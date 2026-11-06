package com.unchunks.echomark.ui.insights

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.components.composeui.ReadableLazyColumn
import com.unchunks.echomark.domain.insights.InterestChange
import com.unchunks.echomark.domain.insights.InterestChangeKind
import com.unchunks.echomark.domain.insights.InterestTrend
import com.unchunks.echomark.domain.insights.LibraryStats
import com.unchunks.echomark.domain.insights.TagCloudResult
import com.unchunks.echomark.domain.insights.TimelineBasis
import com.unchunks.echomark.domain.insights.TimelineRow
import com.unchunks.echomark.ui.common.displayName
import com.unchunks.echomark.ui.components.CompactBookmarkRow
import com.unchunks.echomark.ui.components.EmptyState
import com.unchunks.echomark.ui.components.ErrorState
import com.unchunks.echomark.ui.components.LoadingState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** 画面の 4 つの表示 */
enum class InsightsTab(val label: String) {
    CLOUD("タグクラウド"),
    STATS("統計"),
    TIMELINE("タイムライン"),
    INTERESTS("関心の変化")
}

/**
 * ライブラリの分析(タグクラウド・統計・タイムライン・関心の変化)。ViewModel をつなぐだけの薄いラッパー。
 * @param onOpenTag タグクラウドのタグをタップしたとき(そのタグで絞り込んだ一覧へ戻る)
 * @param onOpenBookmark タイムラインのブックマークをタップしたとき(詳細画面を開く)
 */
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    onOpenTag: (Long) -> Unit,
    onOpenBookmark: (Long) -> Unit,
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    InsightsContent(
        uiState = uiState,
        onBack = onBack,
        onOpenTag = onOpenTag,
        onOpenBookmark = onOpenBookmark,
        onTimelineBasis = viewModel::setTimelineBasis,
        onInterestMonths = viewModel::setInterestMonths
    )
}

/** 画面の本体(状態を受け取って描くだけ)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsContent(
    uiState: InsightsUiState,
    onBack: () -> Unit,
    onOpenTag: (Long) -> Unit,
    onOpenBookmark: (Long) -> Unit,
    onTimelineBasis: (TimelineBasis) -> Unit,
    onInterestMonths: (Int) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: InsightsTab = InsightsTab.CLOUD
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("ライブラリの分析", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            TabChips(tab, onSelect = { tab = it })
            when {
                uiState.isLoading -> LoadingState()
                uiState.errorMessage != null -> ErrorState(message = uiState.errorMessage)
                uiState.bookmarkCount == 0 -> EmptyState(
                    icon = Icons.Outlined.Insights,
                    title = "まだブックマークがありません",
                    description = "ブックマークを保存すると、タグや保存の傾向をここで見られます。"
                )
                else -> ReadableLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    when (tab) {
                        InsightsTab.CLOUD -> item(key = "cloud") { TagCloudSection(uiState.tagCloud, onOpenTag) }
                        InsightsTab.STATS -> item(key = "stats") { StatsSection(uiState.stats) }
                        InsightsTab.TIMELINE -> timelineItems(uiState, onTimelineBasis, onOpenBookmark)
                        InsightsTab.INTERESTS -> item(key = "interests") {
                            InterestsSection(uiState.interests, uiState.interestMonths, onInterestMonths)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChips(selected: InsightsTab, onSelect: (InsightsTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InsightsTab.entries.forEach { entry ->
            FilterChip(selected = selected == entry, onClick = { onSelect(entry) }, label = { Text(entry.label) })
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---- タグクラウド ----

private val CLOUD_MIN_SIZE = 13.sp
private val CLOUD_MAX_SIZE = 30.sp

/** タグを件数に応じた大きさで並べる。タップでそのタグの一覧へ。 */
@Composable
private fun TagCloudSection(cloud: TagCloudResult, onOpenTag: (Long) -> Unit) {
    if (cloud.entries.isEmpty()) {
        Note("タグがまだありません。ブックマークにタグが付くと、ここに並びます。")
        return
    }
    Column {
        Note("大きいほど、そのタグが付いたブックマークが多いタグです。タップするとそのタグの一覧を開きます。")
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            cloud.entries.forEach { entry ->
                val tag = entry.tag
                Text(
                    text = tag.name,
                    modifier = Modifier
                        .sizeIn(minHeight = 48.dp, minWidth = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "「${tag.name}」の一覧を開く") { onOpenTag(tag.id) }
                        .semantics { contentDescription = "${tag.name}、${tag.bookmarkCount}件" }
                        .padding(vertical = 6.dp),
                    fontSize = lerp(CLOUD_MIN_SIZE, CLOUD_MAX_SIZE, entry.weight),
                    fontWeight = if (entry.weight > 0.66f) FontWeight.Bold else FontWeight.Normal,
                    color = if (tag.isUserTag) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (cloud.hiddenCount > 0) Note("ほか ${cloud.hiddenCount} 個のタグは、件数が少ないため省いています。")
        Note("青い字はあなたが付けた(整えた)タグ、灰色の字は AI が付けたタグです。")
    }
}

// ---- 統計 ----

/** 名前と値を 2 列で並べる表 */
@Composable
private fun SimpleTable(header: List<String>, rows: List<List<String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TableRow(header, bold = true)
        HorizontalDivider()
        rows.forEach { row ->
            TableRow(row, bold = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, bold: Boolean) {
    // 行全体で 1 つの項目として読み上げる(「5月、保存 3、読了 2」の形)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, cell ->
            Text(
                cell,
                modifier = if (index == 0) Modifier.weight(1.4f) else Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
                color = if (bold) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun YearMonth.shortLabel(): String = "${monthValue}月"

private fun YearMonth.fullLabel(): String = "${year}年${monthValue}月"

@Composable
private fun StatsSection(stats: LibraryStats) {
    Column {
        SectionTitle("全体")
        SimpleTable(
            header = listOf("項目", "件数"),
            rows = listOf(
                listOf("ブックマーク", "${stats.total}"),
                listOf("未読", "${stats.unread}"),
                listOf("既読", "${stats.read}"),
                listOf("お気に入り", "${stats.favorites}"),
                listOf("アーカイブ", "${stats.archived}"),
                listOf("要約あり", "${stats.withSummary}")
            )
        )

        SectionTitle("種類")
        HorizontalBars(
            entries = stats.byType.map { (type, count) -> type.displayName() to count },
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionTitle("カテゴリ")
        HorizontalBars(
            entries = stats.byCategory.map { it.label to it.count },
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionTitle("月ごとの保存と読了")
        val palette = chartPalette()
        val series = listOf(
            ChartSeries("保存した数", palette[0], stats.monthly.map { it.added }),
            ChartSeries("読んだ数", palette[1], stats.monthly.map { it.read })
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartLegend(series)
            BarChart(
                labels = stats.monthly.map { it.month.shortLabel() },
                series = series,
                summary = "直近${stats.monthly.size}か月の保存数と読了数の棒グラフ。数値は下の表のとおり"
            )
        }
        SimpleTable(
            header = listOf("月", "保存", "読了"),
            rows = stats.monthly.reversed().map { listOf(it.month.fullLabel(), "${it.added}", "${it.read}") },
            modifier = Modifier.padding(top = 8.dp)
        )
        Note("「読了」は詳細画面を開いた、または読んだことにした日で数えています。今月は途中までの数です。")
    }
}

// ---- タイムライン ----

private fun LazyListScope.timelineItems(
    uiState: InsightsUiState,
    onBasis: (TimelineBasis) -> Unit,
    onOpenBookmark: (Long) -> Unit
) {
    item(key = "timeline-basis") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.timelineBasis == TimelineBasis.SAVED,
                onClick = { onBasis(TimelineBasis.SAVED) },
                label = { Text("保存した日") }
            )
            FilterChip(
                selected = uiState.timelineBasis == TimelineBasis.LAST_OPENED,
                onClick = { onBasis(TimelineBasis.LAST_OPENED) },
                label = { Text("最後に開いた日") }
            )
        }
    }
    uiState.timeline.forEach { row ->
        // contentType を分けて、見出しと行の使い回しを混ぜない
        when (row) {
            is TimelineRow.MonthHeader -> item(key = row.key, contentType = "month") {
                Text(
                    "${row.month.fullLabel()}(${row.count}件)",
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            is TimelineRow.DayHeader -> item(key = row.key, contentType = "day") {
                Text(
                    row.date.dayLabel(),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is TimelineRow.Item -> item(key = row.key, contentType = "item") {
                CompactBookmarkRow(
                    bookmark = row.bookmark,
                    onClick = { onOpenBookmark(row.bookmark.id) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun LocalDate.dayLabel(): String {
    val weekday = dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.JAPAN)
    return "${monthValue}月${dayOfMonth}日($weekday)"
}

// ---- 関心の変化 ----

@Composable
private fun InterestsSection(trend: InterestTrend?, months: Int, onMonths: (Int) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("期間", style = MaterialTheme.typography.labelLarge)
            INTEREST_PERIOD_CHOICES.forEach { choice ->
                FilterChip(selected = months == choice, onClick = { onMonths(choice) }, label = { Text("${choice}か月") })
            }
        }
        if (trend == null || trend.isEmpty) {
            Note("この期間に保存したタグ付きのブックマークがありません。期間を長くするか、ブックマークにタグを付けてください。")
            return@Column
        }
        Note("この期間に保存した ${trend.bookmarkCount} 件のタグから、関心の移り変わりを見ています。")

        SectionTitle("よく付いたタグの推移")
        val palette = chartPalette()
        val series = trend.series.mapIndexed { index, s -> ChartSeries(s.tag, palette[index % palette.size], s.counts) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartLegend(series)
            LineChart(
                labels = trend.months.map { it.shortLabel() },
                series = series,
                summary = "上位${series.size}個のタグが、月ごとに何件のブックマークに付いたかの折れ線グラフ。" +
                    series.joinToString("。") { s -> "${s.name}は合計${s.values.sum()}件" }
            )
        }

        ChangeList("増えているテーマ", trend.rising, "前半 → 後半に保存した数が増えたタグです。")
        ChangeList("新しく現れたテーマ", trend.newTopics, "以前は付けていなかったのに、最近付くようになったタグです。")
        ChangeList("減っているテーマ", trend.falling, "前半より後半に保存した数が減ったタグです。")
        Note("タグを付けた日は記録していないため、ブックマークを保存した日で数えています。今月は途中までの数です。")
    }
}

@Composable
private fun ChangeList(title: String, changes: List<InterestChange>, description: String) {
    if (changes.isEmpty()) return
    SectionTitle(title)
    Note(description)
    SimpleTable(
        header = listOf("タグ", "前半", "後半"),
        rows = changes.map { change ->
            val mark = when (change.kind) {
                InterestChangeKind.RISING -> "↑ "
                InterestChangeKind.FALLING -> "↓ "
                InterestChangeKind.NEW -> "★ "
            }
            listOf(mark + change.tag, "${change.earlier}", "${change.recent}")
        }
    )
}
