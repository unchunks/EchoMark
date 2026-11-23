package com.unchunks.echomark.domain.help

import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.domain.repository.ThemeMode

/**
 * 使い方の AI から行える操作。AI は操作を「提案」するだけで、実行は利用者がボタンを押してから
 * (小型の端末内モデルは読み違えることがあり、設定や保存を勝手に変えないため)。
 * ファイルを選ぶ・本人を確かめる・通知の権限を求めるなど、画面での操作が要るものは、その画面を開く([Open])。
 */
sealed interface AppAction {
    /** ボタンに出す文言(何が起きるかが分かるように) */
    val label: String

    /** 画面を開く */
    data class Open(val screen: AppScreen) : AppAction {
        override val label: String get() = "「${screen.title}」を開く"
    }

    /** ブックマークを検索する(一覧を開いて検索語を入れる) */
    data class Search(val query: String) : AppAction {
        override val label: String get() = "「$query」で検索"
    }

    /** リンクをブックマークとして保存する */
    data class SaveLink(val url: String) : AppAction {
        override val label: String get() = "このリンクを保存: $url"
    }

    /** メモを保存する(1行目をタイトルにする) */
    data class SaveNote(val text: String) : AppAction {
        override val label: String get() = "メモとして保存: ${text.lineSequence().first().take(LABEL_PREVIEW_CHARS)}"
    }

    data class SetTheme(val mode: ThemeMode) : AppAction {
        override val label: String
            get() = when (mode) {
                ThemeMode.SYSTEM -> "テーマを「端末の設定に合わせる」にする"
                ThemeMode.LIGHT -> "テーマをライトにする"
                ThemeMode.DARK -> "テーマをダークにする"
            }
    }

    data class SetDesign(val style: DesignStyle) : AppAction {
        override val label: String
            get() = "デザインを「" + when (style) {
                DesignStyle.CLASSIC -> "クラシック"
                DesignStyle.ECHO -> "エコー"
                DesignStyle.PAPER -> "ペーパー"
            } + "」にする"
    }

    data class SetOfflineMode(val enabled: Boolean) : AppAction {
        override val label: String get() = if (enabled) "オフラインモードをオンにする" else "オフラインモードをオフにする"
    }

    data class SetProcessingOption(val option: ProcessingOption, val enabled: Boolean) : AppAction {
        override val label: String get() = "「${option.title}」を${if (enabled) "オン" else "オフ"}にする"
    }

    data class SetSummaryLanguage(val language: SummaryLanguage) : AppAction {
        override val label: String get() = "要約の言語を「${language.label}」にする"
    }

    /** 失敗・準備待ちのブックマークの AI 処理をやり直す */
    data object ReprocessFailed : AppAction {
        override val label: String get() = "失敗・準備待ちを再処理する"
    }

    private companion object {
        const val LABEL_PREVIEW_CHARS = 20
    }
}

/** 処理の設定のうち、オン・オフで切り替えるもの(設定の画面と同じ名前) */
enum class ProcessingOption(val title: String) {
    CHARGING_ONLY("充電中だけ処理する"),
    UNMETERED_ONLY("Wi-Fi のときだけ通信する"),
    NIGHTLY_BATCH("夜間にまとめて処理する")
}

/** 使い方の AI から開ける画面。名前は画面の題名・メニューの名前と同じにする */
enum class AppScreen(val title: String) {
    BOOKMARKS("ブックマーク"),
    ADD_BOOKMARK("ブックマークを追加"),
    UNREAD("未読のブックマーク"),
    NEW_CHAT("新しいチャット"),
    EXPLORE("見つける"),
    SETTINGS("設定"),
    AI_SETTINGS("AI の設定"),
    CATEGORIES("カテゴリ"),
    PROCESSING_QUEUE("処理の状況"),
    OFFLINE_VIDEOS("オフラインの動画"),
    ONBOARDING("はじめにの案内"),
    REVIEW("読む・ふり返る"),
    DISCOVER("資料を探す"),
    INSIGHTS("ライブラリの分析"),
    GRAPH("ナレッジグラフ"),
    PLACES("場所の地図"),
    COLLECTIONS("コレクション"),
    TAGS("タグの管理"),
    DUPLICATES("重複と関連"),
    BROKEN_LINKS("リンク切れ"),
    PODCASTS("ポッドキャスト"),
    READING_DICTIONARY("読みの辞書")
}
