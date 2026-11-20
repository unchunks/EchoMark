package com.unchunks.echomark.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.domain.subscription.Subscription
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.ui.subscription.SubscriptionOperation
import com.unchunks.echomark.ui.subscription.SubscriptionsContent
import com.unchunks.echomark.ui.subscription.SubscriptionsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** 購読(RSS・YouTube)の管理シートの見た目。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionsScreenshotTest {

    @get:Rule
    val screenshot = ScreenshotRule()

    private val now = 1_793_415_845_000L
    private val hour = 3_600_000L

    private val subscriptions = listOf(
        Subscription(
            1, SubscriptionType.RSS, "https://example.com/feed.xml", "開発ブログ", "https://example.com/", createdAt = 1,
            enabled = true, lastCheckedAt = now - 5 * hour, failureCount = 0, lastError = null
        ),
        Subscription(
            2, SubscriptionType.YOUTUBE, "https://www.youtube.com/feeds/videos.xml?channel_id=UCabcdefghijklmnopqrstuv", "あるチャンネル",
            "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv", createdAt = 2, enabled = true, lastCheckedAt = null, failureCount = 0, lastError = null
        ),
        Subscription(
            3, SubscriptionType.RSS, "https://news.example.org/rss", "ニュースサイトのとても長いタイトルがここに入ります", null, createdAt = 3,
            enabled = true, lastCheckedAt = now - hour, failureCount = 2, lastError = "取得できませんでした(HTTP 500)"
        ),
        Subscription(
            4, SubscriptionType.RSS, "https://old.example.net/atom.xml", "止めているフィード", null, createdAt = 4,
            enabled = false, lastCheckedAt = now - 100 * hour, failureCount = 0, lastError = null
        )
    )

    @Test
    fun empty() = screenshot.captureLightDark("subscriptions_empty") {
        Content(SubscriptionsUiState())
    }

    @Test
    fun list() = screenshot.captureLightDark("subscriptions_list") {
        Content(SubscriptionsUiState(subscriptions = subscriptions, message = "「開発ブログ」を購読しました。最新の 3 件を保存しました。これからの新着は毎晩確認します"))
    }

    @Test
    fun adding() = screenshot.captureLightDark("subscriptions_adding") {
        Content(SubscriptionsUiState(subscriptions = subscriptions.take(1), operation = SubscriptionOperation.Adding))
    }

    @Test
    fun checking() = screenshot.captureLightDark("subscriptions_checking") {
        Content(SubscriptionsUiState(subscriptions = subscriptions, operation = SubscriptionOperation.Checking))
    }

    @Composable
    private fun Content(state: SubscriptionsUiState) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SubscriptionsContent(
                state = state,
                onAdd = { _, _ -> },
                onCheckNow = {},
                onSetEnabled = { _, _ -> },
                onRemove = {},
                onDismissMessage = {},
                nowMillis = now
            )
        }
    }
}
