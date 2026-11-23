package com.unchunks.echomark.ui.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.ui.theme.EchoMarkTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一覧の「新しいデザインを試す」のお知らせの状態。 */
data class DesignIntroState(
    /** お知らせを出すか(クラシックのまま、まだ選ぶか閉じるかしていない) */
    val show: Boolean = false,
    /** ダイナミックカラーがオンか(選ぶダイアログで、配色は壁紙に合わせることを伝える) */
    val dynamicColor: Boolean = false
)

/**
 * 設定の「デザイン」を知らない人に、一覧から一度だけ新しいデザインを案内する。
 * 選ぶか閉じるかしたら二度と出さない。設定でクラシック以外を選んでいる人にも出さない。
 */
@HiltViewModel
class DesignIntroViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository
) : ViewModel() {

    val state: StateFlow<DesignIntroState> = combine(
        appSettings.designStyle,
        appSettings.designIntroSeen,
        appSettings.dynamicColor
    ) { style, seen, dynamicColor ->
        DesignIntroState(show = style == DesignStyle.CLASSIC && !seen, dynamicColor = dynamicColor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DesignIntroState())

    /** 案内から選んだデザインを使う。クラシックを選び直しても、案内は見終えたことにする */
    fun choose(style: DesignStyle) {
        viewModelScope.launch {
            appSettings.setDesignStyle(style)
            appSettings.setDesignIntroSeen()
        }
    }

    fun dismiss() {
        viewModelScope.launch { appSettings.setDesignIntroSeen() }
    }
}

/** 一覧の先頭に出す、新しいデザインの案内。「選ぶ」でデザインのダイアログを開く */
@Composable
fun DesignIntroBanner(onChoose: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Style,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        "新しいデザインを選べます",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "大きな見出しと丸い形の「エコー」と、明朝体と紙色の「ペーパー」。設定の「表示」からいつでも変えられます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("閉じる") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onChoose) { Text("デザインを選ぶ") }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DesignIntroBannerPreview() {
    EchoMarkTheme {
        Surface { DesignIntroBanner(onChoose = {}, onDismiss = {}) }
    }
}
