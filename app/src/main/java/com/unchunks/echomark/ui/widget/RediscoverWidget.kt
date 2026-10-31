package com.unchunks.echomark.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.unchunks.echomark.MainActivity
import com.unchunks.echomark.R
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.ui.navigation.LaunchTarget
import com.unchunks.echomark.ui.navigation.Routes
import com.unchunks.echomark.ui.theme.DarkColorScheme
import com.unchunks.echomark.ui.theme.LightColorScheme
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
        // 表示中(セッションが生きている間)は DB の変化に追従する。最初の値は描画前に読んでおき、空の表示を挟まない
        val initialItems = itemsFlow.first()
        val initialDynamicColor = dynamicColorFlow.first()
        provideContent {
            val items by itemsFlow.collectAsState(initialItems)
            val dynamicColor by dynamicColorFlow.collectAsState(initialDynamicColor)
            RediscoverWidgetTheme(dynamicColor) {
                RediscoverWidgetContent(items = items, layout = RediscoverWidgetData.layoutFor(LocalSize.current))
            }
        }
    }

    private fun rediscoverItems(repository: BookmarkRepository): Flow<List<RediscoverWidgetItem>> =
        repository.observeBookmarks()
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
}

/** ウィジェットは Hilt の注入先にできないため、EntryPoint からリポジトリを取る。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RediscoverWidgetEntryPoint {
    fun bookmarkRepository(): BookmarkRepository
    fun appSettingsRepository(): AppSettingsRepository
}

/** アプリと同じく、ブランド配色(既定)か壁紙に合わせたダイナミックカラー。ライト/ダークは端末に合わせる。 */
@Composable
internal fun RediscoverWidgetTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    GlanceTheme(colors = if (dynamicColor) DynamicThemeColorProviders else BrandColors, content = content)
}

private val BrandColors = ColorProviders(light = LightColorScheme, dark = DarkColorScheme)

/**
 * ウィジェットの中身。ヘッダー(「＋」で URL を追加、チャット)と、大きさに応じた件数([layout])のブックマーク。
 * 状態を受け取るだけにして、ユニットテスト(glance-appwidget-testing)やスクリーンショットで中身を確認できるようにしている。
 */
@Composable
internal fun RediscoverWidgetContent(items: List<RediscoverWidgetItem>, layout: RediscoverWidgetLayout) {
    val context = LocalContext.current
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_notification),
                title = if (layout.showTitle) context.getString(R.string.widget_rediscover_title) else "",
                iconColor = GlanceTheme.colors.primary,
                textColor = GlanceTheme.colors.onSurface,
                actions = {
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_add),
                        contentDescription = ADD_LABEL,
                        onClick = actionStartActivity(launchTargetIntent(context, LaunchTarget.ADD_BOOKMARK)),
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurface
                    )
                    CircleIconButton(
                        imageProvider = ImageProvider(R.drawable.ic_widget_chat),
                        contentDescription = CHAT_LABEL,
                        onClick = actionStartActivity(launchTargetIntent(context, LaunchTarget.NEW_CHAT)),
                        backgroundColor = null,
                        contentColor = GlanceTheme.colors.onSurface
                    )
                }
            )
        },
        modifier = GlanceModifier.padding(bottom = 12.dp)
    ) {
        if (items.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = EMPTY_MESSAGE,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                items.take(layout.maxItems).forEachIndexed { index, item ->
                    if (index > 0) Spacer(GlanceModifier.height(6.dp))
                    RediscoverWidgetRow(item, showSource = layout.showSource)
                }
            }
        }
    }
}

@Composable
private fun RediscoverWidgetRow(item: RediscoverWidgetItem, showSource: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(GlanceTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(actionStartActivity(bookmarkDetailIntent(context, item.bookmarkId)))
    ) {
        Text(
            text = item.title,
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
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

/** アプリショートカットと同じ起動先([LaunchTarget])でアプリを開く。 */
internal fun launchTargetIntent(context: Context, target: LaunchTarget): Intent =
    LaunchTarget.putInto(Intent(context, MainActivity::class.java), target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

internal const val EMPTY_MESSAGE = "保存するとここに再発見が表示されます"
internal const val ADD_LABEL = "URL を追加"
internal const val CHAT_LABEL = "新しいチャット"
