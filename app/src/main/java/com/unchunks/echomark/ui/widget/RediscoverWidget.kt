package com.unchunks.echomark.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.unit.ColorProvider
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.navigation.LaunchTarget
import com.unchunks.echomark.ui.navigation.Routes
import com.unchunks.echomark.ui.theme.DarkColorScheme
import com.unchunks.echomark.ui.theme.LightColorScheme
import com.unchunks.echomark.ui.theme.LocalDesignStyle
import com.unchunks.echomark.ui.theme.designColorScheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * ホーム画面ウィジェット「EchoMark 再発見」。しばらく開いていないブックマークを大きさに応じて1〜3件出す。
 * 選び方は一覧上部の「今日の再発見」と同じ([RediscoverWidgetData])。
 * 更新: ブックマークの保存・削除・閲覧時([RediscoverWidgetUpdater])と、1日1回(ウィジェット情報の updatePeriodMillis)。
 */
class RediscoverWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(RediscoverWidgetData.RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, RediscoverWidgetEntryPoint::class.java)
        val itemsFlow = rediscoverItems(entryPoint.bookmarkRepository())
        val dynamicColorFlow = entryPoint.appSettingsRepository().dynamicColor.catch { emit(false) }
        val designStyleFlow = entryPoint.appSettingsRepository().designStyle.catch { emit(DesignStyle.CLASSIC) }
        // AI 処理の進み具合(処理中・処理待ちが無ければ null)。読めなければ出さない
        val processingFlow = entryPoint.widgetProcessingSource().observe().catch { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "再発見ウィジェットの AI 処理の進み具合を読み込めませんでした")
            emit(null)
        }
        // 表示中(セッションが生きている間)は DB の変化に追従する。最初の値は描画前に読んでおき、空の表示を挟まない
        val initialItems = itemsFlow.first()
        val initialDynamicColor = dynamicColorFlow.first()
        val initialDesignStyle = designStyleFlow.first()
        val initialProcessing = processingFlow.first()
        // アプリのロック中は、ホーム画面に内容(ブックマークのタイトル・処理の件数)を出さない。読めなければ隠す方に倒す
        val hiddenFlow = entryPoint.appSettingsRepository().appLock.map { it.enabled }.catch { emit(true) }
        val initialHidden = hiddenFlow.first()
        provideContent {
            val items by itemsFlow.collectAsState(initialItems)
            val dynamicColor by dynamicColorFlow.collectAsState(initialDynamicColor)
            val designStyle by designStyleFlow.collectAsState(initialDesignStyle)
            val processing by processingFlow.collectAsState(initialProcessing)
            val hidden by hiddenFlow.collectAsState(initialHidden)
            RediscoverWidgetTheme(dynamicColor, designStyle) {
                RediscoverWidgetContent(
                    items = items,
                    layout = RediscoverWidgetData.layoutFor(LocalSize.current, hasProcessing = processing != null && !hidden),
                    processing = processing,
                    hidden = hidden
                )
            }
        }
    }

    private fun rediscoverItems(repository: BookmarkRepository): Flow<List<RediscoverWidgetItem>> =
        // 候補になる古い順の数十件だけを、出すのに要る列だけで読む(全件を本文ごと読み直さない)
        repository.observeLeastRecentlyAccessed(RediscoverWidgetData.CANDIDATE_LIMIT)
            .map { RediscoverWidgetData.toItems(RediscoverWidgetData.select(it, System.currentTimeMillis())) }
            .catch { e ->
                if (e is CancellationException) throw e
                // ウィジェットは補助的な表示なので、読めなければ空の案内を出す
                Timber.w(e, "再発見ウィジェットの内容を読み込めませんでした")
                emit(emptyList())
            }
}

/** ウィジェットを置いたときに呼ばれる受け口(AndroidManifest に登録)。 */
class RediscoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RediscoverWidget()

    /** 1つ目を置いた。ブックマークの変化に合わせた描き直しを始める */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updater(context).onPlacementChanged(placed = true)
    }

    /** 最後の1つを外した。DB を見るのをやめる */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        updater(context).onPlacementChanged(placed = false)
    }

    private fun updater(context: Context): RediscoverWidgetUpdater =
        EntryPointAccessors.fromApplication(context, RediscoverWidgetEntryPoint::class.java).rediscoverWidgetUpdater()
}

/** ウィジェットは Hilt の注入先にできないため、EntryPoint からリポジトリを取る。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RediscoverWidgetEntryPoint {
    fun bookmarkRepository(): BookmarkRepository
    fun appSettingsRepository(): AppSettingsRepository
    fun widgetProcessingSource(): WidgetProcessingSource
    fun rediscoverWidgetUpdater(): RediscoverWidgetUpdater
}

/**
 * アプリと同じく、選んだデザイン([designStyle])の配色(既定はブランド配色)か、壁紙に合わせたダイナミックカラー。
 * ライト/ダークは端末に合わせる。デザインは [LocalDesignStyle] で中身に伝え、角丸・地色・見出しの書体を変える。
 */
@Composable
internal fun RediscoverWidgetTheme(
    dynamicColor: Boolean,
    designStyle: DesignStyle = DesignStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val colors = if (dynamicColor) DynamicThemeColorProviders else designColorProviders(designStyle)
    CompositionLocalProvider(LocalDesignStyle provides designStyle) {
        GlanceTheme(colors = colors, content = content)
    }
}

private val BrandColors by lazy { ColorProviders(light = LightColorScheme, dark = DarkColorScheme) }
private val EchoColors by lazy {
    ColorProviders(light = designColorScheme(DesignStyle.ECHO, dark = false), dark = designColorScheme(DesignStyle.ECHO, dark = true))
}
private val PaperColors by lazy {
    ColorProviders(light = designColorScheme(DesignStyle.PAPER, dark = false), dark = designColorScheme(DesignStyle.PAPER, dark = true))
}

private val ClearColors by lazy {
    ColorProviders(light = designColorScheme(DesignStyle.CLEAR, dark = false), dark = designColorScheme(DesignStyle.CLEAR, dark = true))
}
private val SoftColors by lazy {
    ColorProviders(light = designColorScheme(DesignStyle.SOFT, dark = false), dark = designColorScheme(DesignStyle.SOFT, dark = true))
}

private fun designColorProviders(style: DesignStyle) = when (style) {
    DesignStyle.CLASSIC -> BrandColors
    DesignStyle.ECHO -> EchoColors
    DesignStyle.PAPER -> PaperColors
    DesignStyle.CLEAR -> ClearColors
    DesignStyle.SOFT -> SoftColors
}

/** クリアの行。アプリのカードと同じく、ライトは白、ダークは一段明るい灰色(Glance の配色には surfaceContainer の段が無いため直に持つ) */
private val ClearRowColor = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1C1E))

/**
 * デザインごとのウィジェットの見た目。アプリの一覧の「今日の再発見」にそろえる。
 * - エコー: ウィジェット全体を primaryContainer の帯にし、その上に大きな角丸の行を浮かせる
 * - ペーパー: 紙色の地に角の小さい行を並べ、タイトルを明朝体にする
 * - クリア: 淡い灰色(ダークは黒)の地に白い角丸の行
 * - ソフト: 地色の上に一段濃い色の丸い行(ウィジェットでは影を描けないため、色の段差で区切る)
 */
private object WidgetDesign {
    val background: ColorProvider
        @Composable get() = when (LocalDesignStyle.current) {
            DesignStyle.ECHO -> GlanceTheme.colors.primaryContainer
            DesignStyle.PAPER, DesignStyle.CLEAR, DesignStyle.SOFT -> GlanceTheme.colors.surface
            DesignStyle.CLASSIC -> GlanceTheme.colors.widgetBackground
        }

    /** 地([background])に直接載せる文字・アイコンの色 */
    val onBackground: ColorProvider
        @Composable get() =
            if (LocalDesignStyle.current == DesignStyle.ECHO) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface

    val onBackgroundVariant: ColorProvider
        @Composable get() =
            if (LocalDesignStyle.current == DesignStyle.ECHO) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurfaceVariant

    val accent: ColorProvider
        @Composable get() =
            if (LocalDesignStyle.current == DesignStyle.ECHO) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.primary

    /** ブックマークの行の地色。ペーパーは紙の地と同じ色だと区切れないので、一段濃い紙色にする */
    val row: ColorProvider
        @Composable get() = when (LocalDesignStyle.current) {
            DesignStyle.PAPER, DesignStyle.SOFT -> GlanceTheme.colors.surfaceVariant
            DesignStyle.CLEAR -> ClearRowColor
            DesignStyle.CLASSIC, DesignStyle.ECHO -> GlanceTheme.colors.surface
        }

    val corner: Dp
        @Composable get() = when (LocalDesignStyle.current) {
            DesignStyle.ECHO -> 20.dp
            DesignStyle.PAPER -> 4.dp
            DesignStyle.CLASSIC, DesignStyle.CLEAR -> 12.dp
            DesignStyle.SOFT -> 16.dp
        }

    val titleFont: FontFamily?
        @Composable get() = if (LocalDesignStyle.current == DesignStyle.PAPER) FontFamily.Serif else null
}

/**
 * ウィジェットの中身。ヘッダー(「＋」で URL を追加、検索、チャット)と、AI 処理の進み具合([processing]。出すかどうかは [layout] で決まる)、
 * 大きさに応じた件数([layout])のブックマーク。
 * 状態を受け取るだけにして、ユニットテスト(glance-appwidget-testing)やスクリーンショットで中身を確認できるようにしている。
 */
@Composable
internal fun RediscoverWidgetContent(
    items: List<RediscoverWidgetItem>,
    layout: RediscoverWidgetLayout,
    processing: WidgetProcessing? = null,
    /** アプリのロック中。ブックマークと処理の件数を出さず、開くと本人を確かめる */
    hidden: Boolean = false
) {
    val context = LocalContext.current
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_notification),
                title = if (layout.showTitle) context.getString(R.string.widget_rediscover_title) else "",
                iconColor = WidgetDesign.accent,
                textColor = WidgetDesign.onBackground,
                actions = {
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = ADD_LABEL,
                        onClick = actionStartActivity(launchTargetIntent(context, LaunchTarget.ADD_BOOKMARK)),
                        backgroundColor = null,
                        contentColor = WidgetDesign.onBackground
                    )
                    if (layout.showSearch) {
                        CircleIconButton(
                            imageProvider = ImageProvider(R.drawable.ic_widget_search),
                            contentDescription = SEARCH_LABEL,
                            onClick = actionStartActivity(launchTargetIntent(context, LaunchTarget.SEARCH)),
                            backgroundColor = null,
                            contentColor = WidgetDesign.onBackground
                        )
                    }
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_chat),
                        contentDescription = CHAT_LABEL,
                        onClick = actionStartActivity(launchTargetIntent(context, LaunchTarget.NEW_CHAT)),
                        backgroundColor = null,
                        contentColor = WidgetDesign.onBackground
                    )
                }
            )
        },
        modifier = GlanceModifier.padding(bottom = 12.dp),
        backgroundColor = WidgetDesign.background
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            if (hidden) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        .clickable(actionStartActivity(appIntent(context))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = HIDDEN_MESSAGE,
                        style = TextStyle(
                            color = WidgetDesign.onBackgroundVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    )
                }
                return@Column
            }
            if (layout.showProcessing && processing != null) {
                WidgetProcessingStrip(processing)
                Spacer(GlanceModifier.height(6.dp))
            }
            if (items.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text(
                        text = EMPTY_MESSAGE,
                        style = TextStyle(
                            color = WidgetDesign.onBackgroundVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            } else {
                items.take(layout.maxItems).forEachIndexed { index, item ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    RediscoverWidgetRow(item, showSource = layout.showSource)
                }
            }
        }
    }
}

/**
 * AI 処理の進み具合の欄(処理中・処理待ちの件数、進み具合の棒、残り時間の目安)。押すと処理の状況の画面を開く。
 * 棒は処理中のものの進み具合の平均で、処理中のものが無い(待ちだけ)ときは出さない。
 */
@Composable
private fun WidgetProcessingStrip(processing: WidgetProcessing) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(WidgetDesign.corner)
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = processing.description() }
            .clickable(actionStartActivity(processingQueueIntent(context)))
    ) {
        Text(
            text = processing.title(),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        )
        processing.fraction?.let { fraction ->
            Spacer(GlanceModifier.height(4.dp))
            LinearProgressIndicator(
                progress = fraction,
                modifier = GlanceModifier.fillMaxWidth(),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surface
            )
        }
        processing.detail()?.let { detail ->
            Text(
                text = detail,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 12.sp)
            )
        }
    }
}

@Composable
private fun RediscoverWidgetRow(item: RediscoverWidgetItem, showSource: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(WidgetDesign.corner)
            .background(WidgetDesign.row)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(bookmarkDetailIntent(context, item.bookmarkId)))
    ) {
        Text(
            text = item.title,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = WidgetDesign.titleFont
            )
        )
        if (showSource) {
            Text(
                text = item.source,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
            )
        }
    }
}

/** ブックマーク詳細を開く(通知と同じディープリンク echomark://bookmark/{id})。 */
internal fun bookmarkDetailIntent(context: Context, bookmarkId: Long): Intent =
    Intent(Intent.ACTION_VIEW, Routes.bookmarkDeepLink(bookmarkId).toUri(), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** アプリを開く(ロック中なら、開いたところで本人を確かめる) */
internal fun appIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** アプリショートカットと同じ起動先([LaunchTarget])でアプリを開く。 */
internal fun launchTargetIntent(context: Context, target: LaunchTarget): Intent =
    LaunchTarget.putInto(Intent(context, MainActivity::class.java), target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** 処理の状況の画面を開く(まとめての処理の通知と同じディープリンク echomark://processing-queue)。 */
internal fun processingQueueIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Routes.PROCESSING_QUEUE_DEEP_LINK.toUri(), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

internal const val HIDDEN_MESSAGE = "アプリのロック中は内容を表示しません。タップして開きます"
internal const val EMPTY_MESSAGE = "保存するとここに再発見が表示されます"
internal const val SEARCH_LABEL = "検索"
internal const val ADD_LABEL = "URL を追加"
internal const val CHAT_LABEL = "新しいチャット"
