package com.unchunks.echomark.data.remote

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/*
 * URL の本文取得で、端末のローカルネットワーク(ルーター・NAS など)やループバックへ接続しないための判定。
 * 取得した本文はクラウドの LLM に送ることがあるため、共有されたリンク経由で LAN 内の情報を読み出させない(SSRF 対策)。
 */

/**
 * インターネット上の(接続してよい)アドレスか。ループバック・リンクローカル・プライベート・CGNAT・
 * マルチキャスト・予約済みの範囲は拒否する。IPv4 射影・NAT64 の IPv6 は中の IPv4 で判定する。
 */
internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return false
    }
    val bytes = address.address
    return when (bytes.size) {
        4 -> isPublicIpv4(bytes)
        16 -> isPublicIpv6(bytes)
        else -> false
    }
}

private fun isPublicIpv4(b: ByteArray): Boolean {
    val (a0, a1, a2) = Triple(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    return when {
        a0 == 0 || a0 == 10 || a0 == 127 -> false
        a0 == 100 && a1 in 64..127 -> false // CGNAT
        a0 == 169 && a1 == 254 -> false // リンクローカル
        a0 == 172 && a1 in 16..31 -> false
        a0 == 192 && a1 == 168 -> false
        a0 == 192 && a1 == 0 && a2 == 0 -> false // IETF 予約
        a0 == 198 && a1 in 18..19 -> false // ベンチマーク用
        a0 >= 224 -> false // マルチキャスト・予約・ブロードキャスト
        else -> true
    }
}

private fun isPublicIpv6(b: ByteArray): Boolean {
    val first = b[0].toInt() and 0xFF
    // IPv4 射影(::ffff:a.b.c.d)・NAT64(64:ff9b::a.b.c.d)は中の IPv4 で判定する
    val mapped = (0 until 10).all { b[it].toInt() == 0 } &&
        (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
    val nat64 = first == 0x00 && (b[1].toInt() and 0xFF) == 0x64 &&
        (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B && (4 until 12).all { b[it].toInt() == 0 }
    if (mapped || nat64) return isPublicIpv4(b.copyOfRange(12, 16))
    return when {
        (first and 0xFE) == 0xFC -> false // ユニークローカル(fc00::/7)
        first == 0xFE && (b[1].toInt() and 0xC0) == 0xC0 -> false // サイトローカル(fec0::/10)
        else -> true
    }
}

/** リダイレクトを追ってよいか。http/https 以外と、https から http への格下げは追わない。 */
internal fun isAllowedRedirect(from: HttpUrl, to: HttpUrl): Boolean =
    (to.scheme == "http" || to.scheme == "https") && !(from.isHttps && !to.isHttps)

/** 名前解決の結果から接続してはいけないアドレスを除く。残らなければ解決できなかった扱いにする。 */
internal class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val isAllowed: (InetAddress) -> Boolean = ::isPublicAddress
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val allowed = delegate.lookup(hostname).filter(isAllowed)
        if (allowed.isEmpty()) throw UnknownHostException("ローカルネットワークのアドレスには接続しません: $hostname")
        return allowed
    }
}

/**
 * 実際に接続した先のアドレスを確かめる(ネットワークインターセプタ)。IP アドレスを直接指定した URL は
 * 名前解決を通らないため [PublicOnlyDns] では防げない。HTTP リクエストを送る前に止める。
 * プロキシ経由の接続はプロキシ自身のアドレスになるため確かめない。
 */
internal class PublicAddressInterceptor(
    private val isAllowed: (InetAddress) -> Boolean = ::isPublicAddress
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val route = chain.connection()?.route()
        val address = route?.socketAddress?.address
        if (route != null && route.proxy.type() == Proxy.Type.DIRECT && address != null && !isAllowed(address)) {
            throw IOException("ローカルネットワークのアドレスには接続しません")
        }
        return chain.proceed(chain.request())
    }
}
