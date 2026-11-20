package com.unchunks.echomark.crash

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.unchunks.echomark.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * クラッシュの記録を仕込む。外部のクラッシュ収集サービスは使わず、記録は端末の中の [CrashLogStore] にだけ残す。
 * 記録を見る・共有する・消すのは設定画面から(ユーザーの操作でだけ外に出る)。
 */
object CrashReporter {

    @Volatile
    private var store: CrashLogStore? = null

    /** アプリ全体で1つの記録先。 */
    fun store(context: Context): CrashLogStore =
        store ?: synchronized(this) {
            store ?: CrashLogStore(
                dir = File(context.applicationContext.filesDir, "crash-logs"),
                environment = ::environment
            ).also { store = it }
        }

    /**
     * 捕まえられなかった例外を記録するハンドラを入れる。記録のあとは元のハンドラ(システムの異常終了の処理)に渡す。
     * Hilt の準備より前に呼べるよう、依存は [Context] だけにしている。
     */
    fun install(context: Context) {
        val store = store(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            store.record(CrashLogKind.CRASH, throwable, thread.name)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** ワーカーが例外で失敗したときに記録する設定を WorkManager に足す。 */
    fun Configuration.Builder.recordWorkerFailures(context: Context): Configuration.Builder {
        val store = store(context)
        return setWorkerExecutionExceptionHandler { info ->
            store.record(
                CrashLogKind.WORKER,
                info.throwable,
                Thread.currentThread().name,
                note = info.workerClassName
            )
        }
    }

    /** アプリが Timber.e(例外つき)で記録したエラーも残す。release でも植える。 */
    fun errorTree(context: Context): Timber.Tree {
        val store = store(context)
        return object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority < Log.ERROR || t == null) return
                store.record(CrashLogKind.ERROR, t, Thread.currentThread().name, note = message.takeIf { it.isNotBlank() })
            }
        }
    }

    private fun environment() = CrashEnvironment(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    )
}
