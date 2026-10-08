package com.unchunks.echomark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.unchunks.echomark.data.ai.model.EmbeddingModelManager
import com.unchunks.echomark.data.local.OnDeviceEmbeddingProvider
import com.unchunks.echomark.di.DefaultDispatcherProvider
import com.unchunks.echomark.domain.provider.EmbeddingInputBuilder
import com.unchunks.echomark.domain.provider.EmbeddingUnavailableException
import com.unchunks.echomark.domain.search.RetrievalEvaluator
import com.unchunks.echomark.domain.search.RetrievalFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機・エミュレータで、いま有効な埋め込みモデル(取り込み済みならそれ、無ければ同梱)の検索品質を測る。
 * 評価データ(app/src/test/resources/eval/ja_bookmarks.json)を埋め込み、Recall@k・MRR・nDCG@k と、
 * 距離のしきい値の提案を Logcat(タグ EmbeddingEval)と標準出力に出す。
 * モデルが無い環境(assets 未同梱で何も取り込んでいない)では assume により skip する。
 * 手順は docs/embedding-evaluation.md。
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingEvalInstrumentedTest {

    @Test
    fun 有効な埋め込みモデルの検索品質を測る() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val dispatchers = DefaultDispatcherProvider()
        // アプリと同じ構成。取り込み済みのモデルがあればそれが使われる(この計測では取り込み・削除はしない)
        val modelManager = EmbeddingModelManager(target, WorkManager.getInstance(target), dispatchers)
        val provider = OnDeviceEmbeddingProvider(target, modelManager, dispatchers)

        val json = instrumentation.context.assets.open(FIXTURE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val fixture = RetrievalFixture.parse(json)
        val profile = provider.profile

        val report = try {
            val documentVectors = fixture.documents.associate { doc ->
                // アプリが保存するときと同じ入力の作り方(本文は文書のテキスト、要約は無し)
                val input = EmbeddingInputBuilder.build(doc.title, null, doc.text, profile)
                doc.id to provider.embedDocument(input)
            }
            val queryVectors = fixture.queries.associate { it.id to provider.embedQuery(it.text) }
            RetrievalEvaluator.evaluate(fixture, documentVectors, queryVectors)
        } catch (e: EmbeddingUnavailableException) {
            assumeNoException("埋め込みモデルが無いため評価できない", e)
            return@runBlocking
        }

        val summary = "モデル: ${profile.displayName} (${profile.modelVersion})\n" +
            "現在の値: maxSearchDistance = ${profile.maxSearchDistance} / minRagSimilarity = ${profile.minRagSimilarity}\n" +
            report.format()
        Log.i(TAG, summary)
        println(summary)

        // 品質の合否は決めない(モデルごとに比べるための計測)。少なくとも何かは取れていることだけ確かめる
        assertTrue("1 件も正解が上位に来ない: モデルや入力の作り方を確認する", report.recallAt.getValue(5) > 0.0)
    }

    private companion object {
        const val TAG = "EmbeddingEval"

        /** app/src/test/resources/eval/ を androidTest の assets に入れている(app/build.gradle.kts) */
        const val FIXTURE_ASSET = "ja_bookmarks.json"
    }
}
