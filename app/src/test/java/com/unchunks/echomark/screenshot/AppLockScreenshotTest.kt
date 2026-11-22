package com.unchunks.echomark.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.ui.lock.AppLockContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** アプリのロック中の画面(#100)。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLockScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun locked() = screenshot.captureScreenLightDark("app_lock") {
        AppLockContent(message = null, noDeviceLock = false, onUnlock = {}, onTurnOff = {})
    }

    /** 試した回数が多すぎたなど、理由を出している */
    @Test
    fun lockedWithMessage() = screenshot.captureScreenLightDark("app_lock_message") {
        AppLockContent(
            message = "試した回数が多すぎます。しばらく待つか、端末の画面ロックで解除してください。",
            noDeviceLock = false,
            onUnlock = {},
            onTurnOff = {}
        )
    }

    /** 端末に画面ロックが無く、本人を確かめられない */
    @Test
    fun noDeviceLock() = screenshot.captureScreenLightDark("app_lock_no_device_lock") {
        AppLockContent(message = null, noDeviceLock = true, onUnlock = {}, onTurnOff = {})
    }
}
