package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.testing.FakeSecretCipher
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class ApiKeyRepositoryImplTest {

    // 実ファイルの DataStore は Windows 上の JVM で上書き rename に失敗することがあるため、メモリ上の実装を使う
    private val dataStore = InMemoryPreferencesDataStore()
    private val cipher = FakeSecretCipher()
    private val repository = ApiKeyRepositoryImpl(dataStore, cipher, TestDispatcherProvider(Dispatchers.Unconfined))

    @Test
    fun 保存したキーを復号して取り出せる() = runBlocking {
        repository.setKey(ApiProvider.CLAUDE, "  sk-ant-secret-value  ")

        assertEquals("sk-ant-secret-value", repository.getKey(ApiProvider.CLAUDE))
        assertNull(repository.getKey(ApiProvider.OPENAI))
        assertEquals(setOf(ApiProvider.CLAUDE), repository.configuredProviders.first())
    }

    @Test
    fun 保存される値に平文のキーが含まれない() = runBlocking {
        repository.setKey(ApiProvider.GEMINI, "AIzaPlainTextKey")

        val stored = dataStore.data.first().asMap().values.joinToString()
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains("AIzaPlainTextKey"))
    }

    @Test
    fun 空文字の保存と削除でキーが消える() = runBlocking {
        repository.setKey(ApiProvider.OPENAI, "sk-proj-1")
        repository.setKey(ApiProvider.OPENAI, "   ")
        assertNull(repository.getKey(ApiProvider.OPENAI))

        repository.setKey(ApiProvider.OPENAI, "sk-proj-2")
        repository.clearKey(ApiProvider.OPENAI)
        assertNull(repository.getKey(ApiProvider.OPENAI))
        assertTrue(repository.configuredProviders.first().isEmpty())
    }

    @Test
    fun 復号できないキーは破棄して未設定扱い() = runBlocking {
        repository.setKey(ApiProvider.CLAUDE, "sk-ant-x")
        cipher.failDecrypt = true

        assertNull(repository.getKey(ApiProvider.CLAUDE))
        assertTrue(repository.configuredProviders.first().isEmpty())
    }

    @Test
    fun 認証タグが合わないキーは破棄する() = runBlocking {
        repository.setKey(ApiProvider.CLAUDE, "sk-ant-x")
        cipher.decryptFailure = AEADBadTagException("tag mismatch")

        assertNull(repository.getKey(ApiProvider.CLAUDE))
        assertTrue(repository.configuredProviders.first().isEmpty())
    }

    @Test
    fun Base64として壊れている値は破棄する() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("api_key_openai")] = "%%% not base64 %%%" }

        assertNull(repository.getKey(ApiProvider.OPENAI))
        assertTrue(repository.configuredProviders.first().isEmpty())
    }

    @Test
    fun Keystoreの一時的なエラーではキーを消さない() = runBlocking {
        repository.setKey(ApiProvider.GEMINI, "AIza-key")
        cipher.decryptFailure = ProviderException("Keystore operation failed")

        assertNull(repository.getKey(ApiProvider.GEMINI))
        assertEquals(setOf(ApiProvider.GEMINI), repository.configuredProviders.first())

        // 回復すれば同じキーをそのまま使える
        cipher.decryptFailure = null
        assertEquals("AIza-key", repository.getKey(ApiProvider.GEMINI))
    }

    @Test
    fun 鍵の読み込みの失敗ではキーを消さない() = runBlocking {
        repository.setKey(ApiProvider.CLAUDE, "sk-ant-y")
        cipher.decryptFailure = KeyStoreException("keystore locked")

        assertNull(repository.getKey(ApiProvider.CLAUDE))
        assertEquals(setOf(ApiProvider.CLAUDE), repository.configuredProviders.first())
    }
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
