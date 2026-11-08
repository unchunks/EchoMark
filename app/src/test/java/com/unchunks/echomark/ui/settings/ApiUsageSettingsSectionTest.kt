package com.unchunks.echomark.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiUsageSettingsSectionTest {

    @Test
    fun 金額は米ドルで小数点以下2桁_1セント未満は未満で出す() {
        assertEquals("\$0.42", formatUsd(0.42))
        assertEquals("\$12.00", formatUsd(12.0))
        assertEquals("\$0.01", formatUsd(0.01))
        assertEquals("\$0.01 未満", formatUsd(0.004))
        assertEquals("\$0.01 未満", formatUsd(0.0))
    }

    @Test
    fun 費用の見出しは価格の分からない分を含めていないことが分かる() {
        assertEquals("費用の目安: 約 \$0.42", costLabel(0.42, hasUnpricedUsage = false))
        assertEquals("費用の目安: 約 \$0.42(一部のみ)", costLabel(0.42, hasUnpricedUsage = true))
        assertEquals("費用の目安: 出せません", costLabel(null, hasUnpricedUsage = true))
    }

    @Test
    fun トークン数は3桁区切りで回数つき() {
        assertEquals("3 回 ・ 入力 12,345 / 出力 1,200 トークン", tokensLabel(3, 12_345, 1_200))
    }

    @Test
    fun 注意書きは目安であることと価格を確かめた日を載せる() {
        val note = apiUsageNote()

        assertTrue(note, note.contains("目安"))
        assertTrue(note, note.contains("2026-10-30"))
    }
}
