package com.unchunks.echomark.data.ai.api

import com.unchunks.components.llm.LlmException
import com.unchunks.components.llm.cloud.ApiCredentials
import com.unchunks.components.llm.cloud.ApiLlmClient
import com.unchunks.components.llm.cloud.ApiRequest
import com.unchunks.components.llm.cloud.ApiTokenUsage
import com.unchunks.echomark.data.network.NetworkGate
import com.unchunks.echomark.data.network.OfflineModeException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * オフラインモードの間、クラウド API を呼ばない [ApiLlmClient]。
 * Claude の SDK(anthropic-java)は自前の OkHttp で通信し、共通の OkHttpClient のインターセプターを通らないため、
 * 呼び出しの手前で断る。失敗は通信の失敗([LlmException.Network]、原因は [OfflineModeException])として投げる
 * (AI 処理のワーカーは原因を見て、失敗にせず処理待ちのまま残す)。
 */
internal class OfflineGuardedApiLlmClient(
    private val delegate: ApiLlmClient,
    private val gate: NetworkGate
) : ApiLlmClient {

    override val provider = delegate.provider

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials): String {
        ensureOnline()
        return delegate.complete(request, credentials)
    }

    override suspend fun complete(request: ApiRequest, credentials: ApiCredentials, onUsage: (ApiTokenUsage) -> Unit): String {
        ensureOnline()
        return delegate.complete(request, credentials, onUsage)
    }

    override fun stream(request: ApiRequest, credentials: ApiCredentials): Flow<String> = flow {
        ensureOnline()
        emitAll(delegate.stream(request, credentials))
    }

    override fun stream(request: ApiRequest, credentials: ApiCredentials, onUsage: (ApiTokenUsage) -> Unit): Flow<String> = flow {
        ensureOnline()
        emitAll(delegate.stream(request, credentials, onUsage))
    }

    private fun ensureOnline() {
        if (gate.isOffline()) throw LlmException.Network(OfflineModeException("cloud AI: ${provider.name}"))
    }
}
