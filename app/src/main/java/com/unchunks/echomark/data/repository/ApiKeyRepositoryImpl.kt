package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.securestorage.EncryptedSecretStore
import com.unchunks.components.securestorage.SecretCipher
import com.unchunks.echomark.di.qualifier.ApiKeyStore
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API キーを [SecretCipher] で暗号化し、暗号文(Base64)を専用の DataStore に保存する([EncryptedSecretStore])。
 * 平文のキーはメモリ上で必要なときだけ復号し、ログや例外メッセージには出さない。
 */
@Singleton
class ApiKeyRepositoryImpl @Inject constructor(
    @param:ApiKeyStore dataStore: DataStore<Preferences>,
    cipher: SecretCipher,
    dispatcherProvider: DispatcherProvider
) : ApiKeyRepository {

    private val store = EncryptedSecretStore(dataStore, cipher, dispatcherProvider)

    override val configuredProviders: Flow<Set<ApiProvider>> = store.storedNames.map { names ->
        ApiProvider.entries.filter { nameOf(it) in names }.toSet()
    }

    override suspend fun getKey(provider: ApiProvider): String? = store.get(nameOf(provider))

    override suspend fun setKey(provider: ApiProvider, apiKey: String) = store.set(nameOf(provider), apiKey)

    override suspend fun clearKey(provider: ApiProvider) = store.clear(nameOf(provider))

    /** 保存するときの名前(以前の版と同じ。変えると保存済みのキーを読めなくなる) */
    private fun nameOf(provider: ApiProvider) = "api_key_${provider.name.lowercase()}"
}
