package com.unchunks.echomark.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.unchunks.echomark.domain.websearch.WebSource
import com.unchunks.echomark.ui.navigation.centeredMaxWidth

/**
 * 入力欄の、Web 検索のオン・オフのボタン。オンの間は色を付ける(質問の語句が端末の外に出るため、オンだと分かるように)。
 */
@Composable
internal fun WebSearchToggleButton(enabled: Boolean, onChange: (Boolean) -> Unit, isSending: Boolean) {
    IconToggleButton(
        checked = enabled,
        onCheckedChange = onChange,
        enabled = !isSending,
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.semantics {
            contentDescription = "Web 検索"
            stateDescription = if (enabled) "オン" else "オフ"
        }
    ) {
        Icon(if (enabled) Icons.Outlined.Public else Icons.Outlined.PublicOff, contentDescription = null)
    }
}

/**
 * Web 検索をオンにしている間、入力欄の上に出す帯。何が端末の外に出るかを書き、すぐにオフにできるようにする。
 * オンでも使えない(オフラインモード・キーが無い)ときは、その理由を出す。
 */
@Composable
internal fun ChatWebSearchStatus(state: ChatWebSearchState, onTurnOff: () -> Unit) {
    if (!state.enabled) return
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (state.active) colors.tertiaryContainer else colors.surfaceContainerHigh,
        contentColor = if (state.active) colors.onTertiaryContainer else colors.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .centeredMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = webSearchStatusText(state),
                style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onTurnOff) { Text("オフにする") }
        }
    }
}

/** Web 検索の帯の説明。 */
internal fun webSearchStatusText(state: ChatWebSearchState): String = when {
    state.availability.offline -> "Web 検索はオンですが、オフラインモードのため検索しません"
    !state.availability.keyConfigured -> "Web 検索はオンですが、Brave Search API のキーがありません"
    else -> "Web 検索オン: 質問の語句を Brave Search に送り、結果のページを読みに行きます"
}

/** 直近の質問で Web を検索できなかった理由など。 */
@Composable
internal fun ChatWebSearchNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .centeredMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
    )
}

/** キーが無いのに Web 検索をオンにしようとしたときの案内。 */
@Composable
internal fun WebSearchKeyPromptDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
        title = { Text("Web 検索のキーが必要です") },
        text = {
            Text(
                "AI 設定で Brave Search API のキーを入れると、質問に合わせて Web を検索し、保存したブックマークと一緒に答えの根拠にできます。" +
                    "オンにした会話では、質問の語句が Brave に送られます。",
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph)
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("AI 設定を開く") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

/**
 * 回答が引用した Web のページの1件。保存したブックマーク([AnswerSources] のカード)と見分けられるよう、
 * 地球のアイコンとドメインを出し、枠の色を変える。押すとページを開き、右のボタンでブックマークに保存する。
 */
@Composable
internal fun CitedWebSourceCard(
    source: CitedWebSource,
    isHighlighted: Boolean,
    isSaved: Boolean,
    onOpen: (String) -> Unit,
    onSave: (WebSource) -> Unit
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) bringIntoView.bringIntoView()
    }
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = { onOpen(source.source.url) },
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView),
        border = BorderStroke(if (isHighlighted) 2.dp else 1.dp, if (isHighlighted) colors.primary else colors.tertiary),
        colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WebSourceIcon(number = source.number)
                Spacer(Modifier.width(10.dp))
                WebSourceTitle(source.source, modifier = Modifier.weight(1f))
                WebSourceSaveButton(source.source, isSaved, onSave)
            }
            source.quote?.let { quote ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)) {
                            append(quote)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Paragraph),
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
        }
    }
}

/** 文脈に入れたが回答が引用しなかった Web のページ(畳んだ一覧の中に、保存の後に並べる)。 */
@Composable
internal fun UncitedWebSourceCards(
    sources: List<WebSource>,
    savedUrls: Set<String>,
    onOpen: (String) -> Unit,
    onSave: (WebSource) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sources.forEach { source ->
            OutlinedCard(
                onClick = { onOpen(source.url) },
                modifier = Modifier.width(232.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WebSourceIcon(number = source.number)
                    Spacer(Modifier.width(10.dp))
                    WebSourceTitle(source, modifier = Modifier.weight(1f))
                    WebSourceSaveButton(source, source.url in savedUrls, onSave)
                }
            }
        }
    }
}

@Composable
private fun WebSourceIcon(number: Int) {
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(18.dp)
                .background(MaterialTheme.colorScheme.tertiary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiary
            )
        }
    }
}

@Composable
private fun WebSourceTitle(source: WebSource, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = source.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Web・${source.domain}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WebSourceSaveButton(source: WebSource, isSaved: Boolean, onSave: (WebSource) -> Unit) {
    IconButton(onClick = { onSave(source) }, enabled = !isSaved) {
        Icon(
            if (isSaved) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
            contentDescription = if (isSaved) "保存しました" else "ブックマークに保存",
            tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
