package com.unchunks.echomark.domain.repository

import com.unchunks.components.llm.ApiProvider
import kotlinx.coroutines.flow.Flow

/**
 * クラウド API のキーを暗号化して保存する。
 * キー本体は UI に返さない([getKey] は API 呼び出し側だけが使う)。ログにも出さないこと。
 */
interface ApiKeyRepository {
    /** キーが保存されている提供元の集合。 */
    val configuredProviders: Flow<Set<ApiProvider>>

    /** 復号したキー。未設定、または復号できない(端末移行で鍵が変わった等)なら null。 */
    suspend fun getKey(provider: ApiProvider): String?

    /** 前後の空白を除いて保存する。空なら削除と同じ。 */
    suspend fun setKey(provider: ApiProvider, apiKey: String)

    suspend fun clearKey(provider: ApiProvider)

    /** Web 検索(Brave Search API)のキーを保存しているか。 */
    val webSearchKeyConfigured: Flow<Boolean>

    /** 復号した Web 検索のキー。未設定・復号できなければ null。 */
    suspend fun getWebSearchKey(): String?

    /** 前後の空白を除いて保存する。空なら削除と同じ。 */
    suspend fun setWebSearchKey(apiKey: String)

    suspend fun clearWebSearchKey()
}
