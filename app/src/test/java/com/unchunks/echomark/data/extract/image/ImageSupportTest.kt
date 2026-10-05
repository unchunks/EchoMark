package com.unchunks.echomark.data.extract.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSupportTest {

    @Test
    fun 縮小の倍率は長辺が上限の2倍未満になるまでの2のべき乗() {
        assertEquals(1, ImageSupport.inSampleSize(1000, 800, maxSide = 2048))
        assertEquals(1, ImageSupport.inSampleSize(4095, 100, maxSide = 2048))
        assertEquals(2, ImageSupport.inSampleSize(4096, 3072, maxSide = 2048))
        assertEquals(4, ImageSupport.inSampleSize(3000, 12000, maxSide = 2048))
        assertEquals(1, ImageSupport.inSampleSize(0, 0))
    }

    @Test
    fun 長辺を上限に収める() {
        assertEquals(2048 to 1536, ImageSupport.fitWithin(4000, 3000, maxSide = 2048))
        assertEquals(100 to 50, ImageSupport.fitWithin(100, 50, maxSide = 2048))
        assertEquals(1 to 2048, ImageSupport.fitWithin(1, 10000, maxSide = 2048))
    }

    @Test
    fun EXIFの日時を整える() {
        assertEquals("2024年5月3日 14:05", ImageSupport.formatExifDateTime("2024:05:03 14:05:11"))
        assertEquals("2024年12月31日 9:00", ImageSupport.formatExifDateTime("2024:12:31 09:00:00\u0000"))
        assertNull(ImageSupport.formatExifDateTime("0000:00:00 00:00:00"))
        assertNull(ImageSupport.formatExifDateTime("    :  :     :  :  "))
        assertNull(ImageSupport.formatExifDateTime(null))
    }

    @Test
    fun 画像の本文はあるものだけを入れる() {
        assertEquals(
            "画像内の文字:\n営業時間 10:00〜18:00\n\n写っているもの: 建物, 空\n\n撮影日時: 2024年5月3日 14:05",
            ImageSupport.composeImageText("営業時間 10:00〜18:00\n", listOf("建物", "空"), "2024年5月3日 14:05")
        )
        assertEquals("写っているもの: 猫", ImageSupport.composeImageText(" ", listOf("猫"), null))
        // 撮影日時だけでは中身の手がかりにならないので入れない
        assertEquals("", ImageSupport.composeImageText("", emptyList(), "2024年5月3日 14:05"))
    }

    @Test
    fun ラベルは日本語にして手がかりにならないものを除く() {
        val labels = ImageLabelsJa.translate(
            listOf("Food", "Sitting", "Cuisine", "Bento", "Unknown label", "Stuffed toy", "Plush", "Dog", "Cat")
        )

        // 表に無いラベルは英語のまま。同じ訳(ぬいぐるみ)は1つにまとめ、最大 6 件
        assertEquals(listOf("食べ物", "料理", "弁当", "Unknown label", "ぬいぐるみ", "犬"), labels)
    }
}
