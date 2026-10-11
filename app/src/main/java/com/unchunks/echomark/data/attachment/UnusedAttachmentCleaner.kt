package com.unchunks.echomark.data.attachment

import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.di.DispatcherProvider
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * どのブックマークからも参照されなくなった添付ファイルを、しばらく経ってから消す。
 * ブックマークの削除は「元に戻す」で取り消せるため、削除と同時には消さない(削除時にファイルの更新日時を今にして、
 * そこから [GRACE_MILLIS] 経ったものだけを消す)。取り込みの途中で残ったファイルも同じ規則で消える。
 */
@Singleton
class UnusedAttachmentCleaner @Inject constructor(
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val attachmentStore: Lazy<AttachmentStore>,
    private val bookmarkDao: Lazy<BookmarkDao>,
    dispatcherProvider: DispatcherProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    /** 掃除をバックグラウンドで行う(Application.onCreate から1回呼ぶ)。 */
    fun start() {
        scope.launch {
            try {
                val count = cleanUp()
                if (count > 0) Timber.i("使われていない添付ファイルを削除: $count 件")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "添付ファイルの掃除に失敗")
            }
        }
    }

    /** 参照されていないファイルのうち、[now] から [GRACE_MILLIS] より前に更新されたものを消す。消した数を返す。 */
    suspend fun cleanUp(now: Long = System.currentTimeMillis()): Int {
        // 先に参照を読む。この後に保存されたブックマークのファイルは新しいため猶予の内で消えない
        val referenced = bookmarkDao.get().getAllFilePaths().toSet()
        return attachmentStore.get().deleteUnreferenced(referenced, olderThanMillis = now - GRACE_MILLIS)
    }

    companion object {
        /** 参照されなくなってから消すまでの猶予 */
        val GRACE_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
    }
}
