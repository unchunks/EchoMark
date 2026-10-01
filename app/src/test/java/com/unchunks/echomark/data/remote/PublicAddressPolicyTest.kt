package com.unchunks.echomark.data.remote

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PublicAddressPolicyTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun インターネット上のアドレスは許可する() {
        listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "2001:4860:4860::8888", "2606:4700:4700::1111")
            .forEach { assertTrue(it, isPublicAddress(ip(it))) }
    }

    @Test
    fun ループバック_リンクローカル_プライベートなどは拒否する() {
        listOf(
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "224.0.0.1", "255.255.255.255", "192.0.0.1",
            "198.18.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:192.168.1.1", "::ffff:127.0.0.1", "64:ff9b::a00:1"
        ).forEach { assertFalse(it, isPublicAddress(ip(it))) }
    }

    @Test
    fun プライベートに近いが公開されている範囲は許可する() {
        listOf("172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.169.0.1", "64:ff9b::808:808")
            .forEach { assertTrue(it, isPublicAddress(ip(it))) }
    }

    @Test
    fun DNSの結果からローカルのアドレスを除く() {
        val dns = PublicOnlyDns(Dns { listOf(ip("192.168.0.10"), ip("93.184.216.34")) })

        assertEquals(listOf(ip("93.184.216.34")), dns.lookup("example.com"))
    }

    @Test
    fun ローカルのアドレスしか無いホストは解決できない扱いにする() {
        val dns = PublicOnlyDns(Dns { listOf(ip("127.0.0.1"), ip("10.0.0.5")) })

        assertThrows(UnknownHostException::class.java) { dns.lookup("intranet.example") }
    }

    @Test
    fun httpsからhttpへのリダイレクトは追わない() {
        assertTrue(isAllowedRedirect("http://a.example/".toHttpUrl(), "https://a.example/".toHttpUrl()))
        assertTrue(isAllowedRedirect("https://a.example/".toHttpUrl(), "https://b.example/x".toHttpUrl()))
        assertTrue(isAllowedRedirect("http://a.example/".toHttpUrl(), "http://b.example/".toHttpUrl()))
        assertFalse(isAllowedRedirect("https://a.example/".toHttpUrl(), "http://a.example/".toHttpUrl()))
    }
}
