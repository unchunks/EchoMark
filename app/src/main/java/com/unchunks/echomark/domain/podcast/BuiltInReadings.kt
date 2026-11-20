package com.unchunks.echomark.domain.podcast

/**
 * アプリに入れてある読み。ブックマークによく出る略語・製品名・サービス名のうち、1文字ずつ読むと違うもの・英語の綴りのまま読むと
 * 読み誤りやすいもの。利用者の辞書と AI の読みが先に効くため、ここの読みが合わなければ辞書で直せる。
 * 1文字ずつ読めば合う略語(`API`・`URL` など)は入れない([SpeechNormalizer] が読む)。
 */
object BuiltInReadings {
    val entries: List<ReadingEntry> = listOf(
        // 単語として読む略語
        "NASA" to "ナサ", "JAXA" to "ジャクサ", "UNESCO" to "ユネスコ", "UNICEF" to "ユニセフ", "NATO" to "ナトー",
        "ASEAN" to "アセアン", "OPEC" to "オペック", "JSON" to "ジェイソン", "JPEG" to "ジェイペグ", "GIF" to "ジフ",
        "RAM" to "ラム", "ROM" to "ロム", "LAN" to "ラン", "WAN" to "ワン", "SaaS" to "サース", "PaaS" to "パース",
        "IEEE" to "アイトリプルイー", "SQL" to "シークエル", "YAML" to "ヤムル", "NVIDIA" to "エヌビディア", "ASCII" to "アスキー",
        "LINE" to "ライン", "NEWS" to "ニュース", "LIVE" to "ライブ", "FREE" to "フリー", "SALE" to "セール", "NOTE" to "ノート",
        "TV" to "テレビ", "DX" to "ディーエックス", "SDGs" to "エスディージーズ", "LGBTQ" to "エルジービーティーキュー",
        "vs" to "対", "vs." to "対", "etc." to "など", "Inc." to "インク",
        // 製品・サービス・会社
        "Wi-Fi" to "ワイファイ", "WiFi" to "ワイファイ", "Bluetooth" to "ブルートゥース", "iPhone" to "アイフォーン",
        "iPad" to "アイパッド", "iOS" to "アイオーエス", "macOS" to "マックオーエス", "Mac" to "マック", "Android" to "アンドロイド",
        "Windows" to "ウィンドウズ", "Linux" to "リナックス", "Google" to "グーグル", "Apple" to "アップル", "Microsoft" to "マイクロソフト",
        "Amazon" to "アマゾン", "Meta" to "メタ", "GitHub" to "ギットハブ", "GitLab" to "ギットラボ", "Git" to "ギット",
        "YouTube" to "ユーチューブ", "ChatGPT" to "チャットジーピーティー", "OpenAI" to "オープンエーアイ",
        "Anthropic" to "アンソロピック", "Claude" to "クロード", "Gemini" to "ジェミニ", "Gemma" to "ジェマ",
        "Kotlin" to "コトリン", "Java" to "ジャバ", "JavaScript" to "ジャバスクリプト", "TypeScript" to "タイプスクリプト",
        "Python" to "パイソン", "Rust" to "ラスト", "Swift" to "スウィフト", "C++" to "シープラスプラス", "C#" to "シーシャープ",
        ".NET" to "ドットネット", "Node.js" to "ノードジェイエス", "React" to "リアクト", "Docker" to "ドッカー",
        "Kubernetes" to "クバネティス", "Slack" to "スラック", "Zoom" to "ズーム", "Notion" to "ノーション", "Qiita" to "キータ",
        "Zenn" to "ゼン", "note" to "ノート", "Instagram" to "インスタグラム", "Facebook" to "フェイスブック", "TikTok" to "ティックトック",
        "Wikipedia" to "ウィキペディア", "Netflix" to "ネットフリックス", "Spotify" to "スポティファイ", "Pixel" to "ピクセル",
        "Galaxy" to "ギャラクシー", "Xperia" to "エクスペリア", "PlayStation" to "プレイステーション", "Nintendo" to "ニンテンドー",
        "EchoMark" to "エコーマーク"
    ).map { (surface, reading) -> ReadingEntry(surface, reading) }
}
