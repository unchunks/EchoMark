package com.unchunks.echomark.domain.lock

import com.unchunks.components.applock.AppLockPolicy

/**
 * アプリのロック(#100)の設定。開くときに、指紋・顔・端末の画面ロック(PIN など)で本人を確かめる。
 * いつロックするかと Activity の見張りは、部品の app-lock(`AppLockManager`)が [toPolicy] で受け取って決める。
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

/** 部品(app-lock)に渡すロックの決まり */
fun AppLockSettings.toPolicy(): AppLockPolicy = AppLockPolicy(enabled = enabled, delayMillis = delay.millis, hideContent = hideContent)
