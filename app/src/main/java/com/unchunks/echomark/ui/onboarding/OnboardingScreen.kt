package com.unchunks.echomark.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.ui.settings.japaneseParagraph
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

/** オンボーディングのページ数。 */
const val ONBOARDING_PAGE_COUNT = 4

/** オンボーディングの操作。既定は何もしない(スクリーンショット用)。 */
class OnboardingActions(
    val onChooseAi: (AiSetupChoice) -> Unit = {},
    /** 再発見通知をオンにする(通知権限の要求は呼び出し側で行う) */
    val onEnableRediscover: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onFinish: (OnboardingExit) -> Unit = {}
)

/**
 * 初回起動時(と設定からの再表示)の案内。[onFinish] に、終えた後に開く画面を渡す。
 * スキップ・完了のどちらでも「オンボーディング済み」を保存する。
 */
@Composable
fun OnboardingScreen(
    onFinish: (OnboardingExit) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var notificationDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enableRediscover() else notificationDenied = true
    }

    OnboardingContent(
        uiState = uiState,
        notificationDenied = notificationDenied,
        actions = OnboardingActions(
            onChooseAi = viewModel::chooseAi,
            onEnableRediscover = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.enableRediscover()
                }
            },
            onSkip = {
                onFinish(OnboardingExit.LIST)
                viewModel.complete()
            },
            onFinish = { exit ->
                onFinish(exit)
                viewModel.complete()
            }
        )
    )
}

/**
 * オンボーディングの中身。ページ送り・スキップ・ページインジケーターを持つ。
 * @param initialPage スクリーンショットテストで特定のページを表示する用
 */
@Composable
fun OnboardingContent(
    uiState: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
    notificationDenied: Boolean = false,
    initialPage: Int = 0
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { ONBOARDING_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            // 上部: スキップ(最後のページでは不要なので出さない。高さは保って位置をずらさない)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isLastPage) {
                    TextButton(onClick = actions.onSkip) { Text("スキップ") }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> SavePage()
                    2 -> AiSetupPage(selected = uiState.aiChoice, onChoose = actions.onChooseAi)
                    else -> RediscoverPage(
                        settings = uiState.rediscover,
                        notificationDenied = notificationDenied,
                        onEnable = actions.onEnableRediscover
                    )
                }
            }

            // 下部: ページインジケーターと「次へ」/「はじめる」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PageIndicator(pagerState = pagerState, modifier = Modifier.weight(1f))
                if (isLastPage) {
                    Button(onClick = { actions.onFinish(uiState.exit) }) {
                        Text(if (uiState.exit == OnboardingExit.AI_SETTINGS) "AI の設定へ進む" else "はじめる")
                    }
                } else {
                    Button(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } }) {
                        Text("次へ")
                    }
                }
            }
        }
    }
}

/** 現在のページを横長の点で示す。TalkBack では「ページ 2 / 4」と読む。 */
@Composable
private fun PageIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    val current = pagerState.currentPage
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "ページ ${current + 1} / ${pagerState.pageCount}"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pagerState.pageCount) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "indicatorWidth")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

// ---- 各ページ ----

/** ページ共通のレイアウト: 波紋つきのアイコン・見出し・説明・ページ固有の内容。 */
@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    body: String,
    /** 下の内容が多いページでは、イラストを小さくして1画面に収める */
    compact: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
        EchoIcon(icon, diameter = if (compact) 128.dp else 176.dp)
        Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.japaneseParagraph(),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge.japaneseParagraph(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 同心円の波紋に囲まれたアイコン(「響き返す」イメージ)。装飾なので読み上げない。 */
@Composable
private fun EchoIcon(icon: ImageVector, diameter: Dp) {
    val ring = MaterialTheme.colorScheme.primary
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(1.5.dp.toPx())
            val max = size.minDimension / 2 - stroke.width
            drawCircle(ring.copy(alpha = 0.10f), radius = max, style = stroke)
            drawCircle(ring.copy(alpha = 0.18f), radius = max * 0.82f, style = stroke)
            drawCircle(ring.copy(alpha = 0.28f), radius = max * 0.66f, style = stroke)
        }
        Box(
            Modifier
                .size(diameter * 0.55f)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(diameter * 0.27f)
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        icon = Icons.Outlined.Bookmarks,
        title = "EchoMark へようこそ",
        body = "保存した記事やメモを AI が整理して、忘れた頃にもう一度届けます。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureRow(Icons.Outlined.AutoAwesome, "AI が要約とタグを自動で付けます")
            FeatureRow(Icons.Outlined.Forum, "言葉の意味で探せて、保存した内容に質問できます")
            FeatureRow(Icons.Outlined.NotificationsActive, "しばらく開いていないものを知らせます")
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
    }
}

@Composable
private fun SavePage() {
    OnboardingPage(
        icon = Icons.Outlined.Share,
        title = "保存はブラウザの共有から",
        body = "読みたい記事を見つけたら、共有メニューから EchoMark を選ぶだけです。"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepRow(1, "ブラウザなどで記事を開く")
            StepRow(2, "共有ボタンから「EchoMarkに保存」を選ぶ")
            StepRow(3, "本文を取り込み、AI が要約とタグを付けます")
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge.japaneseParagraph())
    }
}

@Composable
private fun AiSetupPage(selected: AiSetupChoice?, onChoose: (AiSetupChoice) -> Unit) {
    OnboardingPage(
        icon = Icons.Outlined.AutoAwesome,
        title = "AI の準備",
        body = "要約やチャットに使う AI を選んでください。あとから変えられます。",
        compact = true
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(
                icon = Icons.Outlined.PhoneAndroid,
                title = "端末内で動かす",
                description = "プライバシー重視。モデルファイルの取り込みが必要です",
                selected = selected == AiSetupChoice.LOCAL,
                onClick = { onChoose(AiSetupChoice.LOCAL) }
            )
            ChoiceCard(
                icon = Icons.Outlined.Cloud,
                title = "クラウド API を使う",
                description = "すぐ使えます。Claude・Gemini・OpenAI の API キーが必要です",
                selected = selected == AiSetupChoice.API,
                onClick = { onChoose(AiSetupChoice.API) }
            )
            ChoiceCard(
                icon = Icons.Outlined.Schedule,
                title = "あとで決める",
                description = "保存はすぐできます。AI の処理は準備ができてから行います",
                selected = selected == AiSetupChoice.LATER,
                onClick = { onChoose(AiSetupChoice.LATER) }
            )
        }
    }
}

/** 行全体をタップ対象にした選択肢のカード。 */
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.outlinedShape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium.japaneseParagraph(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun RediscoverPage(settings: RediscoverSettings, notificationDenied: Boolean, onEnable: () -> Unit) {
    val day = settings.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.JAPANESE)
    val time = "%d:%02d".format(settings.hour, settings.minute)
    OnboardingPage(
        icon = Icons.Outlined.NotificationsActive,
        title = "忘れた頃に、もう一度",
        body = "30日以上開いていないブックマークを、週に1回($day $time)お知らせします。曜日と時刻は設定で変えられます。"
    ) {
        when {
            settings.enabled -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("通知をオンにしました", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = onEnable) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("通知をオンにする")
                }
                if (notificationDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "通知が許可されませんでした。あとから設定の「通知」でオンにできます。",
                        style = MaterialTheme.typography.bodyMedium.japaneseParagraph(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "オンにしなくても、すぐに使い始められます。",
                        style = MaterialTheme.typography.bodySmall.japaneseParagraph(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
