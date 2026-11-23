package com.unchunks.echomark.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.chat.AssistantAvatar
import com.unchunks.echomark.ui.chat.UserMessageItem
import com.unchunks.echomark.ui.components.SectionHeader
import com.unchunks.echomark.ui.design.AccentCard
import com.unchunks.echomark.ui.design.DesignCard
import com.unchunks.echomark.ui.design.EchoMarkButton
import com.unchunks.echomark.ui.design.EchoMarkExtendedFab
import com.unchunks.echomark.ui.design.EchoMarkFilterChip
import com.unchunks.echomark.ui.design.EchoMarkOutlinedButton
import com.unchunks.echomark.ui.design.EchoMarkSwitch
import com.unchunks.echomark.ui.design.EchoMarkTonalButton
import com.unchunks.echomark.ui.design.EchoMarkTopAppBar
import com.unchunks.echomark.ui.design.GroupPosition
import com.unchunks.echomark.ui.design.GroupedRow
import com.unchunks.echomark.ui.design.SurfaceRole
import com.unchunks.echomark.ui.design.designSegmentColors
import com.unchunks.echomark.ui.design.designSegmentShape
import com.unchunks.echomark.ui.settings.SettingsItem
import com.unchunks.echomark.ui.settings.SettingsNotice
import com.unchunks.echomark.ui.settings.SettingsSwitchItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * デザインごとの部品の見本帳。上のバー・カード(中立・中の区画・選んだもの・強調)・ボタン・チップ・スイッチ・
 * 切り替えボタン・設定のまとまり・会話の吹き出し・追加ボタンを、1枚に並べて撮る。
 * デザインの描き分け(ui/design)を変えたときに、ほかのデザインとの違いと、アプリ内での一貫性をまとめて見比べる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h1400dp-xhdpi")
class DesignGalleryScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    @Test
    fun classic() = capture(DesignStyle.CLASSIC)

    @Test
    fun echo() = capture(DesignStyle.ECHO)

    @Test
    fun paper() = capture(DesignStyle.PAPER)

    @Test
    fun clear() = capture(DesignStyle.CLEAR)

    @Test
    fun soft() = capture(DesignStyle.SOFT)

    private fun capture(style: DesignStyle) =
        screenshot.captureLightDark("design_gallery_${style.name.lowercase()}", widthDp = 420, designStyle = style) {
            Gallery()
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Gallery() {
        val colors = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EchoMarkTopAppBar(title = { Text("ブックマーク") })
            SectionHeader("カード")
            DesignCard(classic = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("ふつうのカード", style = MaterialTheme.typography.titleMedium)
                    Text("一覧のブックマークや情報のまとまり", color = colors.onSurfaceVariant)
                    DesignCard(
                        role = SurfaceRole.Nested,
                        classic = colors.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("中の区画(補足・引用)", modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DesignCard(
                    role = SurfaceRole.Selected,
                    classic = colors.surfaceContainerHighest,
                    border = androidx.compose.foundation.BorderStroke(2.dp, colors.primary),
                    modifier = Modifier.weight(1f)
                ) { Text("選んだもの", modifier = Modifier.padding(16.dp)) }
                AccentCard(containerColor = colors.secondaryContainer, modifier = Modifier.weight(1f)) {
                    Text("強調", modifier = Modifier.padding(16.dp))
                }
            }
            SectionHeader("ボタン・チップ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EchoMarkButton(onClick = {}) { Text("保存") }
                EchoMarkTonalButton(onClick = {}) { Text("要約") }
                EchoMarkOutlinedButton(onClick = {}) { Text("取消") }
            }
            // 幅いっぱい・等分の指定は、影を付けるデザインでもそのまま効く(押せる範囲の確保で縮めない)
            EchoMarkButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("この条件で探す") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EchoMarkOutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("もう一度") }
                EchoMarkTonalButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("覚えた") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EchoMarkFilterChip(selected = true, onClick = {}, label = { Text("すべて") })
                EchoMarkFilterChip(selected = false, onClick = {}, label = { Text("お気に入り") })
                EchoMarkSwitch(checked = true, onCheckedChange = {})
                EchoMarkSwitch(checked = false, onCheckedChange = {})
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("今週", "今月", "すべて").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == 0,
                        onClick = {},
                        shape = designSegmentShape(index, 3),
                        colors = designSegmentColors()
                    ) { Text(label) }
                }
            }
            SectionHeader("設定のまとまり")
            Column {
                GroupedRow(GroupPosition(first = true, last = false), horizontalInset = 0.dp) {
                    SettingsItem(title = "テーマ", icon = Icons.Outlined.BrightnessMedium, summary = "端末の設定に合わせる", onClick = {})
                }
                GroupedRow(GroupPosition(first = false, last = false), horizontalInset = 0.dp) {
                    SettingsItem(title = "デザイン", icon = Icons.Outlined.Style, summary = "クリア", onClick = {})
                }
                GroupedRow(GroupPosition(first = false, last = true), horizontalInset = 0.dp) {
                    SettingsSwitchItem(title = "ダイナミックカラー", icon = null, checked = false, onCheckedChange = {})
                }
            }
            SettingsNotice(text = "お知らせは中の区画として描きます", icon = Icons.Outlined.Info)
            SectionHeader("会話")
            UserMessageItem(text = "保存した記事の要点を教えて", onCopy = {})
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistantAvatar()
                Text("AI の回答は吹き出しにせず、幅いっぱいで読めるようにします。", modifier = Modifier.weight(1f))
            }
            EchoMarkExtendedFab(
                text = { Text("追加") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {}
            )
        }
    }
}
