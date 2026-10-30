package com.unchunks.echomark.data.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** おすすめのモデルの一覧の形。値そのものの出どころは [ModelCatalog] の説明を参照。 */
class ModelCatalogTest {

    @Test
    fun idは重ならず_確かめた日を持つ() {
        val ids = ModelCatalog.entries.map { it.id }

        assertEquals(ids.size, ids.distinct().size)
        LocalDate.parse(ModelCatalog.CHECKED_ON)
    }

    @Test
    fun ファイルはコミットに固定し_確かめられる値を持つ() {
        ModelCatalog.entries.forEach { entry ->
            assertTrue(entry.id, COMMIT.matches(entry.revision))
            assertTrue(entry.id, SHA256.matches(entry.sha256))
            assertTrue(entry.id, entry.sizeBytes > 0)
            assertEquals(entry.id, "https://huggingface.co/${entry.repo}/resolve/${entry.revision}/${entry.fileName}", entry.downloadUrl)
            assertTrue(entry.id, entry.repo.startsWith("litert-community/"))
        }
    }

    @Test
    fun 取り込める形式のモデルだけを載せる() {
        ModelCatalog.entries.forEach { entry ->
            assertEquals(entry.id, "litertlm", entry.format)
            assertTrue(entry.id, ModelImportValidator.supportedExtension(entry.fileName) != null)
        }
    }

    @Test
    fun 同意の要らないモデルはアプリからダウンロードでき_Gemma3は同意が要る() {
        assertEquals(
            listOf("gemma3-1b-int4"),
            ModelCatalog.entries.filter { it.requiresAgreement }.map { it.id }
        )
        assertFalse(ModelCatalog.entries.first { it.id == "gemma-4-e2b" }.requiresAgreement)
    }

    @Test
    fun 説明とライセンスが空でない() {
        ModelCatalog.entries.forEach { entry ->
            assertTrue(entry.id, entry.license.isNotBlank())
            assertTrue(entry.id, entry.suitedFor.isNotBlank())
        }
    }

    private companion object {
        val COMMIT = Regex("[0-9a-f]{40}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
