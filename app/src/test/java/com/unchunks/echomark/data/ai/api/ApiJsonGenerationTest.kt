package com.unchunks.echomark.data.ai.api

import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 要約の応答(JSON)をストリームで受け、JSON が閉じた時点で打ち切る。 */
class ApiJsonGenerationTest {

    /** [stream] を流す Fake。stream() が呼ばれた回数と、上流が最後まで流れたかを記録する。 */
    private class FakeStreamClient(private val body: Flow<String>) : ApiLlmClient {
        override val provider = ApiProvider.CLAUDE
        var streamCalls = 0
        var completeCalls = 0
        var upstreamCompleted = false
        var upstreamCancelled = false

        override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
            completeCalls++
            error("要約は complete() を使わない")
        }

        override fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String> {
            streamCalls++
            return body.onCompletion { cause ->
                if (cause == null) upstreamCompleted = true else upstreamCancelled = true
            }
        }
    }

    private val request = ApiRequest(ApiPurpose.ANALYZE, "指示", listOf(ApiMessage(ChatRole.USER, "本文")))
    private val credentials = ApiCredentials("key", "model")

    @Test
    fun JSONが閉じたら残りを待たずに打ち切る() = runTest {
        // JSON を出し終えた後、1 時間待ってから続きを返すモデル(繰り返しや終了待ちの代わり)
        val client = FakeStreamClient(
            flow {
                emit("""{"summary":"要約",""")
                emit(""""tags":["a"]}""")
                delay(60 * 60 * 1000L)
                emit("続き")
            }
        )

        val text = client.completeJson(request, credentials)

        assertEquals("""{"summary":"要約","tags":["a"]}""", text)
        // 仮想時間が進まない = 続きを待っていない
        assertEquals(0L, currentTime)
        assertEquals(1, client.streamCalls)
        assertEquals(0, client.completeCalls)
        // 上流は最後まで流れず、キャンセルされる(HTTP の接続もここで閉じる)
        assertFalse(client.upstreamCompleted)
        assertTrue(client.upstreamCancelled)
    }

    @Test
    fun JSONが閉じた増分に続きが混ざっていても_閉じる位置までにする() = runTest {
        val client = FakeStreamClient(flow { emit("""{"summary":"要約"} と以上です {""") })

        assertEquals("""{"summary":"要約"}""", client.completeJson(request, credentials))
    }

    @Test
    fun コードブロックの前置きや文字列内の括弧があっても最初のオブジェクトで止まる() = runTest {
        val client = FakeStreamClient(
            flow {
                emit("```json\n{\"summary\":\"波括弧 } を含む\\\" 要約\",")
                emit("\"tags\":[]}\n```")
            }
        )

        assertEquals("```json\n{\"summary\":\"波括弧 } を含む\\\" 要約\",\"tags\":[]}", client.completeJson(request, credentials))
    }

    @Test
    fun JSONが閉じないまま終わったら受け取った全文を返す() = runTest {
        val client = FakeStreamClient(flow { emit("""{"summary":"途中"""); emit("""で切れた""") })

        assertEquals("""{"summary":"途中で切れた""", client.completeJson(request, credentials))
        assertTrue(client.upstreamCompleted)
    }

    @Test
    fun 何も受け取れなければUnexpected() = runTest {
        val client = FakeStreamClient(flow { emit("  ") })

        try {
            client.completeJson(request, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Unexpected) {
            // 空の要約を保存せず、再試行させる
        }
    }

    @Test
    fun ストリームの拒否や失敗はそのまま投げる() = runTest {
        val refused = FakeStreamClient(flow { emit("""{"summ"""); throw LlmException.Refused("SAFETY") })
        try {
            refused.completeJson(request, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.Refused) {
            assertEquals("SAFETY", e.reason)
        }

        val failing = FakeStreamClient(flow<String> { throw LlmException.ServerError(503) })
        try {
            failing.completeJson(request, credentials)
            fail("例外が投げられるはず")
        } catch (e: LlmException.ServerError) {
            // ワーカーの再試行に任せる
        }
    }
}
