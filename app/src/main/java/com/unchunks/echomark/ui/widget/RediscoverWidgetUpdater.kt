package com.unchunks.echomark.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ブックマークの保存・削除・閲覧(最終アクセスの更新)や配色の設定が変わり、ウィジェットに出す内容が変わったら
 * 「EchoMark 再発見」ウィジェットを描き直す。アプリのプロセスが動いている間だけ働く
 * (ブックマークの変更はすべてアプリのプロセス内で起きるため、これで足りる。日替わりは updatePeriodMillis で更新する)。
 */
@Singleton
class RediscoverWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val bookmarkRepository: Lazy<BookmarkRepository>,
    private val appSettingsRepository: Lazy<AppSettingsRepository>,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

    /** 監視を始める(Application.onCreate から1回呼ぶ)。 */
    fun start() {
        scope.launch {
            widgetContentChanges(
                bookmarks = bookmarkRepository.get().observeBookmarks(),
                dynamicColor = appSettingsRepository.get().dynamicColor,
                clock = System::currentTimeMillis
            )
                .catch { e ->
                    if (e is CancellationException) throw e
                    Timber.w(e, "再発見ウィジェットの更新の監視が止まりました")
                }
                .collect { updateWidgets() }
        }
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

        /**
         * ウィジェットの表示内容(出すブックマークと配色)が変わったときだけ流す。
         * AI の要約が付いたなど、表示に関係ない変更では流さない。
         */
        @OptIn(FlowPreview::class)
        internal fun widgetContentChanges(
            bookmarks: Flow<List<Bookmark>>,
            dynamicColor: Flow<Boolean>,
            clock: () -> Long,
            debounceMs: Long = DEBOUNCE_MS
        ): Flow<Pair<List<RediscoverWidgetItem>, Boolean>> =
            combine(bookmarks, dynamicColor) { list, dynamic ->
                RediscoverWidgetData.toItems(RediscoverWidgetData.select(list, clock())) to dynamic
            }
                .debounce(debounceMs)
                .distinctUntilChanged()
    }
}
