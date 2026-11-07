package com.unchunks.echomark.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/** いつロックするか(起動したとき・離れてから決めた時間がたったとき・本人を確かめている間は数えない)。 */
class AppLockStateMachineTest {

    private val on = AppLockSettings(enabled = true, delay = AppLockDelay.SECONDS_30)

    private fun machine(settings: AppLockSettings = on) = AppLockStateMachine().apply { onSettings(settings) }

    @Test
    fun 設定を読むまでは何も出さない() {
        assertEquals(AppLockState.UNKNOWN, AppLockStateMachine().state)
    }

    @Test
    fun ロックがオフなら開いたまま() {
        val machine = machine(AppLockSettings(enabled = false))
        machine.onBackground(0)
        machine.onForeground(1_000_000)
        assertEquals(AppLockState.OPEN, machine.state)
    }

    @Test
    fun 起動したときはロックし_本人を確かめると開く() {
        val machine = machine()
        assertEquals(AppLockState.LOCKED, machine.state)

        machine.onAuthenticationStarted()
        machine.onAuthenticationFinished(succeeded = false)
        assertEquals(AppLockState.LOCKED, machine.state)

        machine.onAuthenticationStarted()
        machine.onAuthenticationFinished(succeeded = true)
        assertEquals(AppLockState.OPEN, machine.state)
    }

    @Test
    fun 離れてから決めた時間がたってから戻るとロックする() {
        val machine = machine().apply { onAuthenticationFinished(succeeded = true) }

        machine.onBackground(now = 1_000)
        machine.onForeground(now = 1_000 + 29_999)
        assertEquals(AppLockState.OPEN, machine.state)

        machine.onBackground(now = 100_000)
        machine.onForeground(now = 130_000)
        assertEquals(AppLockState.LOCKED, machine.state)
    }

    @Test
    fun すぐにロックする設定なら離れただけでロックする() {
        val machine = machine(on.copy(delay = AppLockDelay.IMMEDIATELY)).apply { onAuthenticationFinished(succeeded = true) }

        machine.onBackground(now = 5)
        machine.onForeground(now = 5)

        assertEquals(AppLockState.LOCKED, machine.state)
    }

    @Test
    fun 本人を確かめている間に端末のPINの画面に移っても_離れたことにしない() {
        val machine = machine(on.copy(delay = AppLockDelay.IMMEDIATELY))
        machine.onAuthenticationStarted()
        machine.onBackground(now = 0)
        machine.onForeground(now = 60_000)
        machine.onAuthenticationFinished(succeeded = true)

        assertEquals(AppLockState.OPEN, machine.state)
        // 解いたあとに離れたら、またロックする
        machine.onBackground(now = 70_000)
        machine.onForeground(now = 70_001)
        assertEquals(AppLockState.LOCKED, machine.state)
    }

    @Test
    fun アプリの中でオンにしたときは使っている途中なのでロックしない() {
        val machine = machine(AppLockSettings(enabled = false))

        machine.onSettings(on)

        assertEquals(AppLockState.OPEN, machine.state)
        // 次に離れてから戻ればロックする
        machine.onBackground(now = 0)
        machine.onForeground(now = 31_000)
        assertEquals(AppLockState.LOCKED, machine.state)
    }

    @Test
    fun オフにするとロック中でも開く() {
        val machine = machine()

        machine.onSettings(on.copy(enabled = false))

        assertEquals(AppLockState.OPEN, machine.state)
    }

    @Test
    fun 読めない時間の名前はすぐにロックする() {
        assertEquals(AppLockDelay.IMMEDIATELY, AppLockDelay.fromName("YEARS_100"))
        assertEquals(AppLockDelay.IMMEDIATELY, AppLockDelay.fromName(null))
        assertEquals(AppLockDelay.MINUTES_5, AppLockDelay.fromName("MINUTES_5"))
    }
}
