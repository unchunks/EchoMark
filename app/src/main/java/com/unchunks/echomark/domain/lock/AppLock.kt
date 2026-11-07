package com.unchunks.echomark.domain.lock

/**
 * アプリのロック(#100)。開くときに、指紋・顔・端末の画面ロック(PIN など)で本人を確かめる。
 * @property enabled ロックする
 * @property delay アプリを離れてから、次に開くときにロックするまでの時間
 * @property hideContent 最近使ったアプリの一覧に内容を出さず、スクリーンショットも撮れないようにする(FLAG_SECURE)。ロックとは別に選べる
 */
data class AppLockSettings(
    val enabled: Boolean = false,
    val delay: AppLockDelay = AppLockDelay.IMMEDIATELY,
    val hideContent: Boolean = false
)

/** アプリを離れてからロックするまでの時間 */
enum class AppLockDelay(val seconds: Int, val label: String) {
    IMMEDIATELY(0, "すぐに"),
    SECONDS_30(30, "30 秒後"),
    MINUTE_1(60, "1 分後"),
    MINUTES_5(5 * 60, "5 分後"),
    MINUTES_15(15 * 60, "15 分後");

    val millis: Long get() = seconds * 1_000L

    companion object {
        /** 保存した名前から読む。読めなければ(名前が変わった・無くなった)すぐにロックする(安全な方に倒す) */
        fun fromName(name: String?): AppLockDelay = entries.firstOrNull { it.name == name } ?: IMMEDIATELY
    }
}

/** 画面に出すロックの状態 */
enum class AppLockState {
    /** 設定をまだ読んでいない(何も描かない。中身を一瞬でも見せない) */
    UNKNOWN,

    /** ロックしていない(ロックがオフ、または解除済み) */
    OPEN,

    /** ロック中(本人を確かめるまで中身を出さない) */
    LOCKED
}

/**
 * ロックするかを決める。Android に依存しない純粋な処理で、単体テストできる(時刻は [now] で渡す)。
 *
 * - アプリを起動した(プロセスが始まった)ときは、ロックがオンならロックする
 * - アプリを離れて([onBackground])、[AppLockSettings.delay] 以上たってから戻る([onForeground])とロックする
 * - 本人を確かめている間(端末の PIN の画面に移るなど)は、離れたことにしない
 * - アプリの中でロックをオンにしたときは、使っている途中なのでロックしない(オンにする前に本人を確かめる)
 */
class AppLockStateMachine {
    private var settings: AppLockSettings? = null
    private var unlocked = false
    private var backgroundedAt: Long? = null
    private var authenticating = false

    val state: AppLockState
        get() {
            val current = settings ?: return AppLockState.UNKNOWN
            return if (!current.enabled || unlocked) AppLockState.OPEN else AppLockState.LOCKED
        }

    /** 設定を読んだ・変わった */
    fun onSettings(new: AppLockSettings) {
        val previous = settings
        // アプリの中でオンにした(最初に読んだときはロックする)
        if (previous != null && !previous.enabled && new.enabled) unlocked = true
        settings = new
    }

    /** アプリの画面が1つも見えていない状態になった */
    fun onBackground(now: Long) {
        if (!authenticating) backgroundedAt = now
    }

    /** アプリの画面がまた見えた */
    fun onForeground(now: Long) {
        val left = backgroundedAt
        backgroundedAt = null
        val current = settings ?: return
        if (current.enabled && unlocked && left != null && now - left >= current.delay.millis) unlocked = false
    }

    fun onAuthenticationStarted() {
        authenticating = true
    }

    /** 本人を確かめ終えた。[succeeded] ならロックを解く */
    fun onAuthenticationFinished(succeeded: Boolean) {
        authenticating = false
        if (succeeded) {
            unlocked = true
            backgroundedAt = null
        }
    }
}
