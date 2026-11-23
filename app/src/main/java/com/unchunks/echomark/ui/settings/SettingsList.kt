package com.unchunks.echomark.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.design.GroupPosition
import com.unchunks.echomark.ui.design.GroupedRow

/**
 * 設定の一覧の組み立て。見出し([header])で区切った行([row])のまとまり(グループ)を作り、
 * デザインごとのまとまりの描き方([GroupedRow])で並べる。お知らせや AI のカードのように
 * それ自体が入れ物のものは [standalone] で置き、まとまりの外に出す(前後のまとまりはそこで切れる)。
 *
 * 行の並び(条件で出す・出さない)が変わっても、まとまりの先頭・末尾は組み立てるたびに数え直す。
 */
internal class SettingsListBuilder {

    internal sealed interface Entry {
        val key: Any?
    }

    internal class Header(override val key: Any?, val title: String) : Entry
    internal class Row(override val key: Any?, val content: @Composable () -> Unit) : Entry
    internal class Standalone(override val key: Any?, val content: @Composable () -> Unit) : Entry

    internal val entries = mutableListOf<Entry>()

    /** まとまりの見出し。ここから次の見出し・単独の要素までが1つのまとまり */
    fun header(title: String, key: Any? = null) {
        entries += Header(key, title)
    }

    /** まとまりの1行(設定の行) */
    fun row(key: Any? = null, content: @Composable () -> Unit) {
        entries += Row(key, content)
    }

    /** まとまりに入れない要素(お知らせ・カードなど、それ自体が入れ物のもの) */
    fun standalone(key: Any? = null, content: @Composable () -> Unit) {
        entries += Standalone(key, content)
    }
}

/** [SettingsListBuilder] で組み立てた設定の一覧を、この LazyColumn に並べる */
internal fun LazyListScope.settingsList(build: SettingsListBuilder.() -> Unit) {
    val entries = SettingsListBuilder().apply(build).entries
    entries.forEachIndexed { index, entry ->
        when (entry) {
            is SettingsListBuilder.Header ->
                item(key = entry.key, contentType = "settings-header") { SettingsGroupHeader(entry.title) }
            is SettingsListBuilder.Row -> {
                val position = GroupPosition(
                    first = entries.getOrNull(index - 1) !is SettingsListBuilder.Row,
                    last = entries.getOrNull(index + 1) !is SettingsListBuilder.Row
                )
                // 設定の一覧には左右の余白(gutter)があるので、まとまりを端から離さない
                item(key = entry.key, contentType = "settings-row") {
                    GroupedRow(position, horizontalInset = 0.dp) { entry.content() }
                }
            }
            is SettingsListBuilder.Standalone ->
                item(key = entry.key, contentType = "settings-standalone") { entry.content() }
        }
    }
}

/** まとまりの見出し。見出しの描き方はアプリ共通の [SectionHeader] に従う */
@Composable
internal fun SettingsGroupHeader(title: String) {
    SectionHeader(title)
}

