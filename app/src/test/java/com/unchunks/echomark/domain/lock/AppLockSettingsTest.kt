package com.unchunks.echomark.domain.lock

import com.unchunks.components.applock.AppLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** ロックの設定(いつロックするかは部品の app-lock の AppLockStateMachineTest で確かめる)。 */
class AppLockSettingsTest {

    @Test
    fun 読めない時間の名前はすぐにロックする() {
        assertEquals(AppLockDelay.IMMEDIATELY, AppLockDelay.fromName("YEARS_100"))
        assertEquals(AppLockDelay.IMMEDIATELY, AppLockDelay.fromName(null))
        assertEquals(AppLockDelay.MINUTES_5, AppLockDelay.fromName("MINUTES_5"))
    }

    @Test
    fun 部品に渡す決まりは時間をミリ秒にする() {
        assertEquals(
            AppLockPolicy(enabled = true, delayMillis = 30_000, hideContent = true),
            AppLockSettings(enabled = true, delay = AppLockDelay.SECONDS_30, hideContent = true).toPolicy()
        )
        assertEquals(AppLockPolicy(), AppLockSettings().toPolicy())
    }
}
