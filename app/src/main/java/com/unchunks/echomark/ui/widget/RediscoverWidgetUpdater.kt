package com.unchunks.echomark.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.DesignStyle
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ブックマークの保存・削除・閲覧(最終アクセスの更新)や配色・デザインの設定が変わり、ウィジェットに出す内容が変わったら
 * 「EchoMark 再発見」ウィジェットを描き直す。アプリのプロセスが動いていて、ウィジェットを置いている間だけ働く
 * (ブックマークの変更はすべてアプリのプロセス内で起きるため、これで足りる。日替わりは updatePeriodMillis で更新する)。
 */
@Singleton
class RediscoverWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val bookmarkRepository: Lazy<BookmarkRepository>,
    private val appSettingsRepository: Lazy<AppSettingsRepository>,
    private val processingSource: WidgetProcessingSource,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

    /**
     * ウィジェットを置いているか(null はまだ分からない)。置いていない間は DB を見ない
     * (見ていると、AI 処理の段が進むたび・取り込みのたびに候補を読み直すため)。
     */
    private val placed = MutableStateFlow<Boolean?>(null)

    /** 監視を始める(Application.onCreate から1回呼ぶ)。 */
    fun start() {
        // 置いてあるかを調べる。先に置いた・外した知らせ([onPlacementChanged])が来ていれば、そちらを使う
        scope.launch { placed.compareAndSet(null, hasWidgets()) }
        scope.launch {
            whilePlaced(placed.filterNotNull()) {
                widgetContentChanges(
                    bookmarks = bookmarkRepository.get().observeLeastRecentlyAccessed(RediscoverWidgetData.CANDIDATE_LIMIT),
                    dynamicColor = appSettingsRepository.get().dynamicColor,
                    designStyle = appSettingsRepository.get().designStyle,
                    processing = processingSource.observe(),
                    clock = System::currentTimeMillis
                )
            }
                .catch { e ->
                    if (e is CancellationException) throw e
                    Timber.w(e, "再発見ウィジェットの更新の監視が止まりました")
                }
                .collect { updateWidgets() }
        }
        // アプリのロックを切り替えたら、内容を出す・隠すを描き直す
        scope.launch {
            appSettingsRepository.get().appLock.map { it.enabled }.distinctUntilChanged().drop(1)
                .catch { e ->
                    if (e is CancellationException) throw e
                    Timber.w(e, "アプリのロックの設定を読めない")
                }
                .collect { updateWidgets() }
        }
    }

    /** ウィジェットを置いた([placed] = true)・すべて外した(false)。RediscoverWidgetReceiver の onEnabled・onDisabled から呼ぶ */
    fun onPlacementChanged(placed: Boolean) {
        this.placed.value = placed
    }

    /** ウィジェットが1つ以上置いてあるか。調べられなければ置いてあることにする(描き直しが止まらないように) */
    private suspend fun hasWidgets(): Boolean =
        try {
            GlanceAppWidgetManager(context).getGlanceIds(RediscoverWidget::class.java).isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "再発見ウィジェットを置いているか調べられませんでした")
            true
        }

    private suspend fun updateWidgets() {
        try {
            // ウィジェットを置いていなければ何もしない
            if (GlanceAppWidgetManager(context).getGlanceIds(RediscoverWidget::class.java).isEmpty()) return
            RediscoverWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "再発見ウィジェットを更新できませんでした")
        }
    }

    companion object {
        /** まとめて保存・削除したときに、何度も描き直さないための待ち時間 */
        private const val DEBOUNCE_MS = 1_000L

        /** [placed] が true の間だけ [content] を流す。false になったら止める(DB を見るのをやめる) */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal fun <T> whilePlaced(placed: Flow<Boolean>, content: () -> Flow<T>): Flow<T> =
            placed.distinctUntilChanged().flatMapLatest { if (it) content() else emptyFlow() }

        /** ウィジェットの表示内容。これが変わったときだけ描き直す。 */
        internal data class WidgetSnapshot(
            val items: List<RediscoverWidgetItem>,
            val dynamicColor: Boolean,
            val designStyle: DesignStyle,
            val processing: WidgetProcessing?
        )

        /**
         * ウィジェットの表示内容(出すブックマーク・配色・デザイン・AI 処理の進み具合)が変わったときだけ流す。
         * AI の要約が付いたなど、表示に関係ない変更では流さない([WidgetProcessing] は画面に出す粒度にそろえてあるので、
         * 進み具合が数%動いただけでも流さない)。
         */
        @OptIn(FlowPreview::class)
        internal fun widgetContentChanges(
            bookmarks: Flow<List<Bookmark>>,
            dynamicColor: Flow<Boolean>,
            processing: Flow<WidgetProcessing?> = flowOf(null),
            designStyle: Flow<DesignStyle> = flowOf(DesignStyle.CLASSIC),
            clock: () -> Long,
            debounceMs: Long = DEBOUNCE_MS
        ): Flow<WidgetSnapshot> =
            combine(bookmarks, dynamicColor, processing, designStyle) { list, dynamic, progress, style ->
                WidgetSnapshot(RediscoverWidgetData.toItems(RediscoverWidgetData.select(list, clock())), dynamic, style, progress)
            }
                .debounce(debounceMs)
                .distinctUntilChanged()
    }
}
