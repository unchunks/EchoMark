package com.unchunks.echomark.domain.help

import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.repository.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionCatalogTest {

    private fun section(id: String) = AppGuide.sections.first { it.id == id }

    private fun suggest(question: String, answer: String, vararg sectionIds: String): List<AppAction> {
        val sections = sectionIds.map(::section)
        return AppActionCatalog.suggest(question, answer, AppActionCatalog.candidatesFor(sections), sections)
    }

    @Test
    fun 案内の節に書いた操作はすべてカタログにある() {
        AppGuide.sections.flatMap { it.actions }.forEach { id ->
            assertTrue("カタログに無い操作: $id", AppActionCatalog.spec(id) != null)
        }
    }

    @Test
    fun 操作のidは重ならない() {
        val ids = AppActionCatalog.specs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun AIが書いた操作の行をボタンにし_画面からは隠す() {
        val answer = "テーマをダークにします。\n操作: theme_dark"

        assertEquals(listOf(AppAction.SetTheme(ThemeMode.DARK)), suggest("暗い画面がいい", answer, "appearance"))
        assertEquals("テーマをダークにします。", AppActionCatalog.visibleText(answer))
    }

    @Test
    fun 操作の行は箇条書きやコード表記_全角のコロンでも読む() {
        val expected = listOf(AppAction.SetSummaryLanguage(SummaryLanguage.ENGLISH))

        assertEquals(expected, suggest("要約の言語を変えたい", "英語にします。\n操作：summary_en", "summary-style"))
        assertEquals(expected, suggest("要約の言語を変えたい", "英語にします。\n- 操作: `summary_en`", "summary-style"))
        assertEquals("英語にします。", AppActionCatalog.visibleText("英語にします。\n- 操作: `summary_en`"))
    }

    @Test
    fun 示していない操作やでたらめの名前は使わない() {
        // appearance の節だけを示したので、offline_on は候補に無い
        val actions = suggest("見た目を変えたい", "操作: offline_on\n操作: delete_everything", "appearance")

        assertTrue(actions.none { it is AppAction.SetOfflineMode })
    }

    @Test
    fun 値の要る操作は値を読み_URLでなければ保存しない() {
        val sections = listOf(section("save-share"))
        val candidates = AppActionCatalog.candidatesFor(sections)

        assertEquals(
            listOf(AppAction.Search("Kotlin")),
            AppActionCatalog.suggest("探して", "操作: search Kotlin", candidates, sections)
        )
        assertTrue(
            AppActionCatalog.suggest("保存して", "操作: save_link あれ", candidates, sections).none { it is AppAction.SaveLink }
        )
    }

    @Test
    fun 質問のURLはAIが書かなくても保存のボタンにする() {
        val actions = suggest("https://example.com/a を保存して", "共有メニューから保存できます。", "save-share")

        assertEquals(AppAction.SaveLink("https://example.com/a"), actions.first())
    }

    @Test
    fun AIが書き忘れても質問の言葉に当たる操作を出す() {
        assertEquals(listOf(AppAction.SetTheme(ThemeMode.DARK)), suggest("ダークモードにして", "設定の表示で変えられます。", "appearance"))
    }

    @Test
    fun 何も当たらなければ近い節の画面を開くボタンを出す() {
        assertEquals(
            listOf(AppAction.Open(AppScreen.AI_SETTINGS)),
            suggest("準備はどうする?", "設定の AI から行います。", "ai-setup")
        )
    }

    @Test
    fun 書きかけの操作の行は生成中だけ隠す() {
        assertEquals("ダークにします。", AppActionCatalog.visibleText("ダークにします。\n操作", streaming = true))
        assertEquals("ダークにします。\n操作の手順は簡単です", AppActionCatalog.visibleText("ダークにします。\n操作の手順は簡単です", streaming = true))
    }

    @Test
    fun ボタンは上限まで() {
        val actions = suggest(
            "https://a.example https://b.example https://c.example https://d.example",
            "",
            "save-share"
        )
        assertEquals(AppActionCatalog.MAX_SUGGESTIONS, actions.size)
    }
}
