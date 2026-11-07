package com.unchunks.echomark.data.linkcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.unchunks.echomark.data.network.NetworkGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 端末が今インターネットに通信できるか。リンクの確認で「開けなかった」のが、リンク先の問題ではなく
 * 端末の通信が切れていたせいではないかを見分けるために使う(通信の切れた間の失敗を、リンク切れに数えない)。
 */
interface NetworkStatus {
    fun isOnline(): Boolean
}

/**
 * 接続中のネットワークがインターネットに届いていると確かめられている([NetworkCapabilities.NET_CAPABILITY_VALIDATED])とき true。
 * オフラインモードの間は false(断った確認をリンク切れに数えず、途中でやめる)。
 */
class ConnectivityNetworkStatus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gate: NetworkGate
) : NetworkStatus {
    override fun isOnline(): Boolean {
        if (gate.isOffline()) return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
