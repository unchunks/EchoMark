package com.unchunks.echomark.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 画面が、デザインごとに描き分ける部品(ui/design)を通しているか。
 *
 * M3 の部品を直に使うと、その画面だけデザインの描き分け(ペーパーの罫線・クリアの色・ソフトの影など)から外れ、
 * 「設定でデザインを変えても一部の画面だけ変わらない」状態になる。新しい画面を足したときに、ここで気づけるようにする。
 */
class DesignSystemUsageTest {

    /** ui/design の部品に置き換えて使う M3 の部品(import の名前 → 使う部品) */
    private val wrapped = mapOf(
        "Card" to "DesignCard / AccentCard",
        "OutlinedCard" to "DesignCard(classicBorder = ...)",
        "ElevatedCard" to "DesignCard",
        "TopAppBar" to "EchoMarkTopAppBar",
        "LargeTopAppBar" to "EchoMarkLargeTopAppBar",
        "MediumTopAppBar" to "EchoMarkTopAppBar",
        "CenterAlignedTopAppBar" to "EchoMarkCenterAlignedTopAppBar",
        "Button" to "EchoMarkButton",
        "OutlinedButton" to "EchoMarkOutlinedButton",
        "FilledTonalButton" to "EchoMarkTonalButton",
        "ElevatedButton" to "EchoMarkTonalButton",
        "FloatingActionButton" to "EchoMarkExtendedFab / EchoMarkSmallFab",
        "SmallFloatingActionButton" to "EchoMarkSmallFab",
        "LargeFloatingActionButton" to "EchoMarkExtendedFab",
        "ExtendedFloatingActionButton" to "EchoMarkExtendedFab",
        "FilterChip" to "EchoMarkFilterChip",
        "ElevatedFilterChip" to "EchoMarkFilterChip",
        "AssistChip" to "EchoMarkAssistChip",
        "ElevatedAssistChip" to "EchoMarkAssistChip",
        "SuggestionChip" to "EchoMarkSuggestionChip",
        "ElevatedSuggestionChip" to "EchoMarkSuggestionChip",
        "InputChip" to "EchoMarkInputChip",
        "Switch" to "EchoMarkSwitch",
        "ModalBottomSheet" to "EchoMarkModalBottomSheet"
    )

    /** 部品そのものを作る所(ここでは M3 の部品を直に使ってよい) */
    private val implementationDirs = listOf(
        "com/unchunks/echomark/ui/design/",
        "com/unchunks/echomark/ui/theme/",
        // ホーム画面のウィジェットは Glance(Compose の UI 部品とは別物)で、デザインは RediscoverWidgetTheme で描き分ける
        "com/unchunks/echomark/ui/widget/"
    )

    /**
     * 角丸の半径を直に書いてよい所と、その理由。ほかは MaterialTheme.shapes か EchoMarkDesign.roundedShape(classic) を使い、
     * デザインの角の段階に合わせる。
     */
    private val literalShapeAllowed = mapOf(
        // デザインを選ぶダイアログの縮図は、それぞれのデザインの形を、選んでいるデザインに関係なく描く
        "com/unchunks/echomark/ui/settings/SettingsDialogs.kt" to "デザインの縮図",
        // クラシックでの吹き出しの形(四隅とも丸い)を渡す。ほかのデザインは userBubbleStyle が決める
        "com/unchunks/echomark/ui/help/AppHelpScreen.kt" to "クラシックの吹き出しの形"
    )

    private val uiSources: Map<String, String> by lazy {
        val root = File("src/main/java")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .filter { (path, _) -> path.startsWith("com/unchunks/echomark/ui/") }
            .toMap()
    }

    @Test
    fun 画面はデザインの部品を通してM3の部品を使う() {
        assertTrue("画面のソースが見つからない(作業ディレクトリがモジュールでない)", uiSources.isNotEmpty())
        val violations = uiSources
            .filterKeys { path -> implementationDirs.none { path.startsWith(it) } }
            .flatMap { (path, text) ->
                wrapped.mapNotNull { (component, replacement) ->
                    val imported = Regex("""^import androidx\.compose\.material3\.$component$""", RegexOption.MULTILINE)
                        .containsMatchIn(text)
                    if (imported) "$path: material3.$component の代わりに $replacement を使う" else null
                }
            }
        assertTrue("デザインの部品を通していない画面:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun 角丸の半径を直に書かない() {
        val literal = Regex("""RoundedCornerShape\(\s*\d""")
        val violations = uiSources
            .filterKeys { path -> implementationDirs.none { path.startsWith(it) } && path !in literalShapeAllowed }
            .filterValues { literal.containsMatchIn(it) }
            .keys
        assertTrue(
            "角丸の半径を直に書いている(MaterialTheme.shapes か EchoMarkDesign.roundedShape(classic) を使う):\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
