package com.unchunks.echomark.data.feed

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.feed.FeedFetcher
import com.unchunks.components.web.isPublicAddress
import okhttp3.OkHttpClient
import java.net.InetAddress

/** フィードを取りに行くときに名乗る User-Agent */
internal const val FEED_USER_AGENT = "Mozilla/5.0 (Android) EchoMark feed reader"

/** オフラインモードで断られたときに利用者へ見せる文言(設定画面のどこで切れるかを添える) */
internal const val FEED_OFFLINE_MESSAGE = "オフラインモードのため取得しませんでした。設定の「オフラインモード」をオフにしてください"

/**
 * EchoMark のフィードの取得(部品の feed の [FeedFetcher] に、名乗る名前とオフラインモードの文言を入れたもの)。
 * @param isAllowedAddress 接続してよいアドレスか(テストではローカルのサーバーを許可する)
 */
internal fun echoMarkFeedFetcher(
    okHttpClient: OkHttpClient,
    dispatcherProvider: DispatcherProvider,
    isAllowedAddress: (InetAddress) -> Boolean = ::isPublicAddress
): FeedFetcher = FeedFetcher(okHttpClient, dispatcherProvider, FEED_USER_AGENT, FEED_OFFLINE_MESSAGE, isAllowedAddress)
