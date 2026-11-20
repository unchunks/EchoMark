package com.unchunks.echomark.data.attachment

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.OfflineVideoDao
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
 * 動画のオフライン保存のサムネイルも同じ置き場所にあり、同じ規則で消える(ブックマークが無くなった保存の記録は、
 * 起動時のこの掃除で消す。同じプロセスの中の「元に戻す」より後に動くことはない)。
 */
@Singleton
class UnusedAttachmentCleaner @Inject constructor(
    // アプリ起動時(メインスレッド)に DB などを作らないよう、使うときに取り出す
    private val attachmentStore: Lazy<AttachmentStore>,
    private val bookmarkDao: Lazy<BookmarkDao>,
    private val offlineVideoDao: Lazy<OfflineVideoDao>,
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
        val dao = bookmarkDao.get()
        val videos = offlineVideoDao.get()
        // ブックマークが無くなった動画の保存の記録を先に消す(そのサムネイルは参照されなくなり、猶予の後に消える)
        videos.deleteOrphans()
        val referenced = (dao.getAllFilePaths() + dao.getAllSnapshotPaths() + videos.getAllThumbnailPaths()).toSet()
        return attachmentStore.get().deleteUnreferenced(referenced, olderThanMillis = now - GRACE_MILLIS)
    }

    companion object {
        /** 参照されなくなってから消すまでの猶予 */
        val GRACE_MILLIS: Long = TimeUnit.DAYS.toMillis(1)
    }
}
