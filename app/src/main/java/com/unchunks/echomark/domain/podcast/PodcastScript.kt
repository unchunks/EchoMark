package com.unchunks.echomark.domain.podcast

/** ポッドキャストの話し方。label は画面に出す日本語。 */
enum class PodcastFormat(val label: String) {
    /** 1人の語り手が紹介する */
    SOLO("1人で語る"),

    /** 2人(進行役と聞き役)の掛け合いで紹介する */
    DUO("2人の掛け合い")
}

/** 台本の1行を話す人。2人の掛け合いでは声を変える(読み上げの声・高さ)。 */
enum class PodcastSpeaker {
    /** 語り手(1人のとき)・進行役(2人のとき) */
    HOST,

    /** 聞き役(2人のときだけ) */
    GUEST
}

/**
 * 台本の材料にするブックマーク1件。
 * @property index 紹介する順(1 始まり)。出典の「1つ目は」に使う
 * @property kindLabel 「記事」「動画」などの種類の言い方
 * @property sourceName 出典の名前(サイト名・ドメイン)。分からなければ null
 * @property excerpt 本文の先頭(長さは呼び出し側で切り詰める)
 */
data class PodcastSource(
    val bookmarkId: Long,
    val index: Int,
    val title: String,
    val kindLabel: String,
    val sourceName: String?,
    val url: String?,
    val summary: String?,
    val excerpt: String?
)

/**
 * 台本の1行。
 * @property text 画面に出す文(書き言葉のまま。読みの整えは読み上げの直前に行う)
 * @property sourceIndex どのブックマークの紹介か([PodcastSource.index])。始まり・終わりのあいさつは null
 */
data class ScriptLine(val speaker: PodcastSpeaker, val text: String, val sourceIndex: Int? = null)

/** 台本の作り方。label は画面に出す日本語。 */
enum class ScriptGenerator(val label: String) {
    /** AI が台本を書いた */
    AI("AI が書いた台本"),

    /** AI を使えず、要約をそのまま読む */
    SUMMARY_READ("要約の読み上げ")
}

/**
 * ポッドキャストの台本。
 * @property readings AI が見つけた、読み間違えやすい語の読み(この台本だけに使う。利用者の辞書が先に効く)
 * @property generator 台本の作り方。AI が一部の紹介だけ書けなかったときも [ScriptGenerator.AI]
 */
data class PodcastScript(
    val title: String,
    val format: PodcastFormat,
    val lines: List<ScriptLine>,
    val readings: List<ReadingEntry>,
    val generator: ScriptGenerator
)
