package com.unchunks.echomark.data.discover

import com.unchunks.components.websearch.ResourceCandidate
import com.unchunks.components.websearch.ResourceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 資料を探す機能の、検索語・候補の絞り込み・AI への指示・AI の出力の読み取り。 */
class DiscoverySelectionTest {

    private fun candidate(title: String, source: ResourceSource = ResourceSource.ARTICLE, snippet: String = "") =
        ResourceCandidate(url = "https://example.com/${title.hashCode()}", title = title, snippet = snippet, source = source)

    @Test
    fun テーマの文から語を取り出して検索語にする() {
        assertEquals("Jetpack Compose 画面", DiscoverySelection.searchQuery("Jetpack Compose の画面を速くしたい"))
        // 語が取れなければテーマのまま
        assertEquals("すし", DiscoverySelection.searchQuery("すし"))
    }

    @Test
    fun 論文は英数字の語だけで探し_無ければ探さない() {
        assertEquals("Kotlin", DiscoverySelection.paperQuery("Kotlin のコルーチン"))
        assertNull(DiscoverySelection.paperQuery("睡眠の質を上げる方法"))
    }

    @Test
    fun テーマの語が多く出てくる候補を先にし_種類を交互に並べる() {
        val candidates = listOf(
            candidate("関係のない記事"),
            candidate("Kotlin コルーチン入門", snippet = "コルーチンの基本"),
            candidate("Kotlin の動画", ResourceSource.VIDEO),
            candidate("コルーチン", ResourceSource.ENCYCLOPEDIA)
        )

        val selected = DiscoverySelection.candidatesForAi("Kotlin コルーチン", candidates, max = 3)

        assertEquals(listOf("Kotlin コルーチン入門", "Kotlin の動画", "コルーチン"), selected.map { it.title })
    }

    @Test
    fun 指示に番号つきの候補とテーマを入れる() {
        val prompt = DiscoverySelection.instructions(
            "Kotlin コルーチン",
            listOf(candidate("入門記事", snippet = "基本の説明").copy(siteName = "Qiita"), candidate("解説動画", ResourceSource.VIDEO))
        )

        assertTrue(prompt, prompt.contains("テーマ: Kotlin コルーチン"))
        assertTrue(prompt, prompt.contains("[1] (記事・Qiita) 入門記事: 基本の説明"))
        assertTrue(prompt, prompt.contains("[2] (動画) 解説動画"))
        assertTrue(prompt, prompt.contains("最大${DiscoverySelection.MAX_PICKS}件"))
    }

    @Test
    fun AIが選んだ番号と理由を読む() {
        val picks = DiscoverySelection.parsePicks(
            """{"picks": [{"n": 2, "reason": "基本がまとまっている"}, {"n": "1", "reason": ""}]}""",
            count = 3
        )

        assertEquals(listOf(ResourcePick(1, "基本がまとまっている"), ResourcePick(0, null)), picks)
    }

    @Test
    fun 番号だけの配列や壊れたJSONも読む() {
        assertEquals(listOf(1, 3), DiscoverySelection.parsePicks("""{"picks": [2, 4]}""", count = 5).map { it.index })
        assertEquals(
            listOf(ResourcePick(2, "入門向け")),
            DiscoverySelection.parsePicks("""{"picks": [{"n": 3, "reason": "入門向け"""", count = 5)
        )
    }

    @Test
    fun 範囲外と重複の番号は除き_上限の件数までにする() {
        val picks = DiscoverySelection.parsePicks("""{"picks": [0, 1, 1, 9, 2, 3, 4, 5, 6]}""", count = 8, maxPicks = 3)

        assertEquals(listOf(0, 1, 2), picks.map { it.index })
    }

    @Test
    fun 何も読めなければ空() {
        assertEquals(emptyList<ResourcePick>(), DiscoverySelection.parsePicks("すみません", count = 3))
        assertEquals(emptyList<ResourcePick>(), DiscoverySelection.parsePicks("""{"picks": []}""", count = 3))
    }
}
