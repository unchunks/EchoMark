package com.unchunks.echomark.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.testing.TestDispatcherProvider
import com.unchunks.echomark.testing.initTestWorkManager
import com.unchunks.echomark.testing.tearDownTestWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MediaPipe のクラスの初期化に失敗したときの埋め込み。
 * JVM には MediaPipe のネイティブライブラリが無く、TextEmbedder の静的初期化の System.loadLibrary が
 * UnsatisfiedLinkError になる(2回目以降は NoClassDefFoundError)。release で R8 が Flogger の呼び出しを埋め込み、
 * Graph の静的初期化が ExceptionInInitializerError で失敗していたときと同じ種類(LinkageError)の Error を再現する。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceEmbeddingProviderLinkageTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = initTestWorkManager(context)
    }

    @After
    fun tearDown() {
        tearDownTestWorkManager(workManager)
    }

    @Test
    fun ライブラリを初期化できないときは何度呼んでも埋め込みなしの例外にする() = runBlocking {
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val provider = OnDeviceEmbeddingProvider(
            context, EmbeddingModelManager(context, workManager, dispatchers), dispatchers
        )

        // 1回目(UnsatisfiedLinkError)も2回目(NoClassDefFoundError)も、Error のまま投げない
        repeat(2) {
            try {
                provider.embedQuery("テスト")
                fail("埋め込みが成功してしまった")
            } catch (e: EmbeddingUnavailableException) {
                assertTrue("原因は ${e.cause}", e.cause is LinkageError)
            }
        }
    }
}
