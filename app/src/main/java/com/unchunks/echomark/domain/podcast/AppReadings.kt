package com.unchunks.echomark.domain.podcast

import com.unchunks.components.speechtext.BuiltInReadings
import com.unchunks.components.speechtext.ReadingEntry

/** 読み上げで入れてある読み。部品の speech-text の [BuiltInReadings] に、アプリの名前を足したもの */
object AppReadings {
    val builtIn: List<ReadingEntry> = BuiltInReadings.entries + ReadingEntry("EchoMark", "エコーマーク")
}
