package com.unchunks.echomark.ui.lock

import android.app.Application
import android.view.WindowManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.domain.lock.AppLockDelay
import com.unchunks.echomark.domain.lock.AppLockSettings
import com.unchunks.echomark.domain.lock.AppLockState
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** Activity の出入りから、ロックと「内容を隠す」を決める(どの入り口の Activity でも同じ)。 */
@RunWith(AndroidJUnit4::class)
class AppLockManagerTest {

    private val settings = FakeAppSettingsRepository()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    private var now = 1_000L

    private fun manager() =
        AppLockManager(settings, TestDispatcherProvider(Dispatchers.Unconfined)) { now }.also { it.install(application) }

    private fun ActivityController<FragmentActivity>.secure(): Boolean =
        get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun unlock(manager: AppLockManager) {
        manager.onAuthenticationStarted()
        manager.onAuthenticationFinished(succeeded = true)
    }

    @Test
    fun 開くとロックし_離れて決めた時間がたってから戻るとまたロックする() {
        settings.appLockFlow.value = AppLockSettings(enabled = true, delay = AppLockDelay.SECONDS_30)
        val manager = manager()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        assertEquals(AppLockState.LOCKED, manager.state.value)

        unlock(manager)
        assertEquals(AppLockState.OPEN, manager.state.value)

        // すぐ戻ればロックしない
        activity.pause().stop()
        now += 10_000L
        activity.restart().resume()
        assertEquals(AppLockState.OPEN, manager.state.value)

        activity.pause().stop()
        now += 31_000L
        activity.restart().resume()
        assertEquals(AppLockState.LOCKED, manager.state.value)
    }

    @Test
    fun アプリの中で別のActivityに移っても離れたことにしない() {
        settings.appLockFlow.value = AppLockSettings(enabled = true, delay = AppLockDelay.IMMEDIATELY)
        val manager = manager()
        val first = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        unlock(manager)

        // 次の画面が前に出てから、前の画面が止まる
        val second = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        first.pause().stop()
        assertEquals(AppLockState.OPEN, manager.state.value)

        // アプリの画面が全部止まってから戻るとロックする
        second.pause().stop()
        second.restart().resume()
        assertEquals(AppLockState.LOCKED, manager.state.value)
    }

    @Test
    fun 内容を隠す設定ならすべてのActivityの窓に付け_外せば外す() {
        settings.appLockFlow.value = AppLockSettings(hideContent = true)
        manager()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        assertTrue(activity.secure())

        settings.appLockFlow.value = AppLockSettings(hideContent = false)
        assertFalse(activity.secure())
        // ロックがオフなら開いたまま
    }

    @Test
    fun 本人の確認のエラーを画面に出す理由にする() {
        assertEquals(AppLockAuthenticator.Result.Failed(null), AppLockAuthenticator.resultOfError(BiometricPrompt.ERROR_USER_CANCELED, "x"))
        assertEquals(AppLockAuthenticator.Result.NoDeviceLock, AppLockAuthenticator.resultOfError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, "x"))
        assertTrue(
            (AppLockAuthenticator.resultOfError(BiometricPrompt.ERROR_LOCKOUT, "x") as AppLockAuthenticator.Result.Failed)
                .message!!.contains("回数が多すぎ")
        )
        assertEquals(AppLockAuthenticator.Result.Failed("センサーが使えません"), AppLockAuthenticator.resultOfError(BiometricPrompt.ERROR_HW_UNAVAILABLE, "センサーが使えません"))
    }
}
