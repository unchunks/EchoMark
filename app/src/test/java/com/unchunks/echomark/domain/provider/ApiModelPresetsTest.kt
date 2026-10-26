package com.unchunks.echomark.domain.provider

import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.cloud.ApiInputBudget
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelPresetsTest {

    @Test
    fun 同梱のプリセットはすべて従来より大きい上限になる() {
        ApiProvider.entries.forEach { provider ->
            provider.presets.forEach { preset ->
                assertTrue(
                    "$provider ${preset.id}",
                    ApiInputBudget.singleCallChars(provider, preset.id) > ApiInputBudget.FALLBACK_CHARS
                )
            }
        }
    }
}
