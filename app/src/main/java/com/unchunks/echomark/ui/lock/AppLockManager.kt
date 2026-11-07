package com.unchunks.echomark.ui.lock

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.domain.lock.AppLockSettings
import com.unchunks.echomark.domain.lock.AppLockState
import com.unchunks.echomark.domain.lock.AppLockStateMachine
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * アプリのロック(#100)の、ただ1つの持ち主。アプリのすべての Activity の出入りを見て、ロックするかを決める
 * ([AppLockStateMachine])。どの入り口(ランチャー・共有・引用・クイック設定タイル・ウィジェット・通知・ディープリンク)から
 * 開いても同じ状態を見るため、入り口ごとの漏れが出ない。画面は [AppLockGate] がこの状態に従って中身を隠す。
 *
 * - 「内容を隠す」([AppLockSettings.hideContent])の FLAG_SECURE も、ここですべての Activity の窓に付ける
 * - 時刻は端末の起動からの経過時間(時計を戻してもロックの時間をごまかせない)
 * - 状態はプロセスの中だけに持つ。プロセスが終われば、次に開くときは必ずロックから始まる
 */
@Singleton
class AppLockManager internal constructor(
    private val appSettings: AppSettingsRepository,
    private val dispatcherProvider: DispatcherProvider,
    /** 端末の起動からの経過時間(テストで差し替える) */
    private val clock: () -> Long
) : Application.ActivityLifecycleCallbacks {

    @Inject
    constructor(appSettings: AppSettingsRepository, dispatcherProvider: DispatcherProvider) :
        this(appSettings, dispatcherProvider, SystemClock::elapsedRealtime)

    private val machine = AppLockStateMachine()
    private val _state = MutableStateFlow(AppLockState.UNKNOWN)
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    /** 最後に読んだ設定(読む前は null) */
    @Volatile
    var settings: AppLockSettings? = null
        private set

    private var startedActivities = 0
    private val created = mutableListOf<WeakReference<Activity>>()

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.main +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "アプリのロックの設定を読めない") }
    )

    /** Application.onCreate から1回呼ぶ */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        // 状態の更新は、Activity の出入りと同じメインスレッドで行う
        scope.launch {
            appSettings.appLock.collect { new ->
                settings = new
                machine.onSettings(new)
                publish()
                applyWindowFlags()
            }
        }
    }

    /** 本人を確かめる画面を出す(端末の PIN の画面に移っても、アプリを離れたことにしない) */
    fun onAuthenticationStarted() {
        machine.onAuthenticationStarted()
    }

    /** 本人を確かめ終えた */
    fun onAuthenticationFinished(succeeded: Boolean) {
        machine.onAuthenticationFinished(succeeded)
        publish()
    }

    private fun publish() {
        _state.value = machine.state
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        created.removeAll { it.get() == null }
        created += WeakReference(activity)
        applyWindowFlags(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) {
            machine.onForeground(clock())
            publish()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        // 回転などで作り直すときは、アプリを離れていない
        if (startedActivities == 0 && !activity.isChangingConfigurations) {
            machine.onBackground(clock())
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        created.removeAll { it.get().let { ref -> ref == null || ref === activity } }
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun applyWindowFlags() {
        created.mapNotNull { it.get() }.forEach(::applyWindowFlags)
    }

    /**
     * 「内容を隠す」がオンなら、最近使ったアプリの一覧に内容を出さず、スクリーンショットも撮れないようにする。
     * 設定を読む前も付けておく(読むまでの一瞬に、一覧へ内容が残らないように)
     */
    private fun applyWindowFlags(activity: Activity) {
        if (settings?.hideContent != false) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
