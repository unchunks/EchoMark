package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.subscription.FeedCheckSummary
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.Subscription
import kotlinx.coroutines.flow.Flow

/** 購読するフィード(RSS・Atom・YouTube のチャンネル。#78)の管理と確認。 */
interface SubscriptionRepository {
    /** 購読の一覧(登録した順) */
    fun observeAll(): Flow<List<Subscription>>

    /**
     * 入力された URL(サイトの URL・フィードの URL・YouTube のチャンネルや再生リストの URL・チャンネル ID)の購読を追加する。
     * サイトの URL なら、ページからフィードを探す。通信が要る。最初の確認は [checkNow] で行う。
     */
    suspend fun subscribe(input: String): SubscribeOutcome

    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 購読をやめる。保存済みのブックマークは残る */
    suspend fun remove(id: Long)

    /**
     * いま確認して、新しい記事・動画を保存する(利用者が押したとき・購読を追加した直後)。[id] が null なら有効なすべて。
     * 夜間の確認と違い、充電中・Wi-Fi の設定は見ない(利用者が自分で押したため)。
     */
    suspend fun checkNow(id: Long? = null): FeedCheckSummary
}
