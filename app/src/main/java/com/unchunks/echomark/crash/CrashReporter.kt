package com.unchunks.echomark.crash

import android.content.Context
import androidx.work.Configuration
import com.unchunks.components.crashlog.CrashLogKind
import com.unchunks.components.crashlog.CrashLogStore
import com.unchunks.components.crashlog.CrashRecorder
import com.unchunks.echomark.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * クラッシュの記録を仕込む(記録の仕組みは部品の crash-log)。外部のクラッシュ収集サービスは使わず、記録は端末の中にだけ残す。
 * 記録を見る・共有する・消すのは設定画面から(ユーザーの操作でだけ外に出る)。
 */
object CrashReporter {

    /** 記録を置く filesDir の中のディレクトリ */
    private const val DIRECTORY = "crash-logs"

    @Volatile
    private var store: CrashLogStore? = null

    /** アプリ全体で1つの記録先。 */
    fun store(context: Context): CrashLogStore =
        store ?: synchronized(this) {
            store ?: CrashLogStore(
                dir = File(context.applicationContext.filesDir, DIRECTORY),
                environment = {
                    CrashRecorder.androidEnvironment("EchoMark", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.DEBUG)
                }
            ).also { store = it }
        }

    /**
     * 捕まえられなかった例外を記録するハンドラを入れる。記録のあとは元のハンドラ(システムの異常終了の処理)に渡す。
     * Hilt の準備より前に呼べるよう、依存は [Context] だけにしている。
     */
    fun install(context: Context) = CrashRecorder.installUncaughtHandler(store(context))

    /** ワーカーが例外で失敗したときに記録する設定を WorkManager に足す。 */
    fun Configuration.Builder.recordWorkerFailures(context: Context): Configuration.Builder {
        val store = store(context)
        return setWorkerExecutionExceptionHandler { info ->
            store.record(CrashLogKind.WORKER, info.throwable, Thread.currentThread().name, note = info.workerClassName)
        }
    }

    /** アプリが Timber.e(例外つき)で記録したエラーも残す。release でも植える。 */
    fun errorTree(context: Context): Timber.Tree = CrashRecorder.errorTree(store(context))
}
