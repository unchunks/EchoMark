package com.unchunks.echomark.ui.settings.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.provider.EmbeddingModels
import com.unchunks.echomark.domain.repository.EMBEDDING_API_PROVIDERS
import com.unchunks.echomark.domain.repository.EmbeddingSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.ui.design.DesignCard
import com.unchunks.echomark.ui.design.EchoMarkFilterChip
import com.unchunks.echomark.ui.design.designSegmentColors
import com.unchunks.echomark.ui.design.designSegmentShape
import com.unchunks.echomark.ui.settings.japaneseParagraph
import com.unchunks.echomark.ui.settings.shortName

/**
 * 検索用の埋め込み(意味での検索・関連・重複・チャットの資料探しに使うベクトル)を作る場所を選ぶカード。
 * 端末内なら下の「埋め込みモデル」の欄で選んだモデル、クラウド API なら提供元の埋め込み API で作る。
 * クラウドで選べるのは埋め込みの API がある提供元だけ(Claude には無い)。
 * @param ready 選んだ場所で埋め込みを作れるか(クラウド API ならキーを保存しているか)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmbeddingLocationSection(
    setting: EmbeddingSetting,
    ready: Boolean,
    onChange: (EmbeddingSetting) -> Unit
) {
    DesignCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        classic = MaterialTheme.colorScheme.surface,
        classicBorder = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("検索用の埋め込みを作る場所", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "意味での検索・関連するブックマーク・重複の検出・チャットの資料探しに使います。",
                style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LlmBackend.entries.forEachIndexed { index, backend ->
                    SegmentedButton(
                        selected = setting.backend == backend,
                        onClick = { onChange(setting.copy(backend = backend)) },
                        shape = designSegmentShape(index, LlmBackend.entries.size),
                        colors = designSegmentColors()
                    ) {
                        Text(
                            when (backend) {
                                LlmBackend.LOCAL -> "端末内"
                                LlmBackend.API -> "クラウド API"
                            }
                        )
                    }
                }
            }
            val provider = setting.cloudProvider
            if (provider != null) {
                FieldLabel("提供元")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EMBEDDING_API_PROVIDERS.forEach { option ->
                        EchoMarkFilterChip(
                            selected = option == provider,
                            onClick = { onChange(setting.copy(apiProvider = option)) },
                            label = { Text(option.shortName) }
                        )
                    }
                }
                Text(
                    embeddingCloudNotice(provider),
                    style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!ready) {
                    StatusLine(
                        icon = {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        text = "${provider.shortName} の API キーが未設定です(下の「クラウド API のキー」で保存できます)。" +
                            "保存するまで、検索はキーワードだけで動きます",
                        isError = true
                    )
                }
            }
        }
    }
}

/** クラウド API で埋め込みを作るときに、何をどこへ送るかと、切り替えたときに起きること */
internal fun embeddingCloudNotice(provider: ApiProvider): String {
    val model = EmbeddingModels.cloudProfile(provider)?.displayName ?: provider.shortName
    return "$model で作ります。保存したブックマークのタイトル・要約・本文の先頭と、検索やチャットの質問を " +
        "${provider.displayName} に送ります(Claude には埋め込みの API が無いため、選べるのは Gemini と OpenAI です)。" +
        "作る場所を変えると、保存済みのブックマークの検索インデックスを作り直します" +
        "(その間、まだ作り直していないブックマークは意味での検索・関連ブックマーク・チャットに出ません)。"
}
