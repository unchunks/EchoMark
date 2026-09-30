package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unchunks.echomark.data.security.SecretCipher
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.di.qualifier.ApiKeyStore
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.ApiKeyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API キーを [SecretCipher] で暗号化し、暗号文(Base64)を専用の DataStore に保存する。
 * 平文のキーはメモリ上で必要なときだけ復号し、ログや例外メッセージには出さない。
 */
@Singleton
class ApiKeyRepositoryImpl @Inject constructor(
    @param:ApiKeyStore private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcherProvider: DispatcherProvider
) : ApiKeyRepository {

    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val configuredProviders: Flow<Set<ApiProvider>> = data.map { prefs ->
        ApiProvider.entries.filter { !prefs[keyOf(it)].isNullOrEmpty() }.toSet()
    }

    override suspend fun getKey(provider: ApiProvider): String? {
        val encoded = data.first()[keyOf(provider)]?.takeIf { it.isNotEmpty() } ?: return null
        return withContext(dispatcherProvider.default) {
            try {
                String(cipher.decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
            } catch (e: Exception) {
                // 端末の移行・バックアップ復元で Keystore の鍵が失われた場合など。再入力してもらう
                Timber.w("API キーを復号できないため破棄: provider=$provider (${e.javaClass.simpleName})")
                null
            }
        }.also { if (it == null) clearKey(provider) }
    }

    override suspend fun setKey(provider: ApiProvider, apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearKey(provider)
            return
        }
        val encoded = withContext(dispatcherProvider.default) {
            Base64.getEncoder().encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)))
        }
        dataStore.edit { it[keyOf(provider)] = encoded }
    }

    override suspend fun clearKey(provider: ApiProvider) {
        dataStore.edit { it.remove(keyOf(provider)) }
    }

    private fun keyOf(provider: ApiProvider) =
        stringPreferencesKey("api_key_${provider.name.lowercase()}")
}
