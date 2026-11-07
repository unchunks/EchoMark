package com.unchunks.echomark.domain.bookmark.model

/**
 * 1件の処理の段。処理は「本文の取得 → 中身の取り出し → 要約・タグ付け → 埋め込み」の順に、必要な段だけを通る
 * ([com.unchunks.echomark.worker.BookmarkWorkScheduler] が積むワークの順)。
 * 処理中([AiStatus.PROCESSING])の間だけ [ProcessingInfo.stage] に入る。
 *
 * 埋め込みモデルを切り替えたときの全件の作り直し(ReembedAllWorker)は、ブックマークごとの状態を変えない
 * (完了したブックマークの表示を「処理中」に戻さない)。
 */
enum class ProcessingStage {
    /** リンク先の本文・OG 画像の取得(UrlFetchWorker)。リンク先がファイルならダウンロードも含む */
    FETCH_CONTENT,

    /** 保存したファイルからの文字の取り出し(ContentExtractionWorker)。画像の OCR・PDF の読み取り・音声や動画の文字起こし */
    EXTRACT_CONTENT,

    /** 要約・タグ・カテゴリの作成(BookmarkAiProcessingWorker)。1回の解析でまとめて作る */
    ANALYZE,

    /** 意味で探すための埋め込みの作成(BookmarkAiProcessingWorker) */
    EMBED
}

/**
 * 処理が失敗した・準備待ちになった・再試行を待っている理由。
 * DB には名前で保存する。読めない値(新しい版で増えたもの)は [UNKNOWN] として読む。
 */
enum class ProcessingFailureReason {
    /** 端末内 AI のモデルを取り込んでいない */
    MODEL_NOT_AVAILABLE,
    /** クラウド API のキーを設定していない */
    API_KEY_MISSING,
    /** クラウド API のキーが無効 */
    API_KEY_INVALID,
    /** モデル ID の誤りなど、送った内容を API が受け付けない */
    BAD_REQUEST,
    /** 要約に使える中身が無い(読み取れないファイルなど) */
    NOTHING_TO_ANALYZE,
    /** AI が要約を断った(安全フィルタなど) */
    REFUSED,
    /** 生成が時間内に終わらなかった */
    TIMEOUT,
    /** 端末内 AI・埋め込みのライブラリを使えない */
    ENGINE_UNAVAILABLE,
    /** 通信できない */
    NETWORK,
    /** API のレート制限 */
    RATE_LIMITED,
    /** API のサーバー障害 */
    SERVER_ERROR,
    /** システムによる中断などで、試行の回数の上限に達した */
    TOO_MANY_ATTEMPTS,
    /** ユーザーが処理をやめた(処理の状況の画面・まとめての処理の通知から)。再処理するまでワーカーは何もしない */
    CANCELLED,
    /** 上のどれにも当たらない */
    UNKNOWN
}

/** 処理の状況の画面に出すもの(#65)。 */
data class ProcessingQueue(
    /** 処理中(段の名前付き) */
    val running: List<Bookmark> = emptyList(),
    /** 処理待ち(頼んだ順) */
    val waiting: List<Bookmark> = emptyList(),
    /** 最近失敗したもの(新しい順) */
    val recentFailures: List<Bookmark> = emptyList()
)

/** ユーザーが処理をやめたものか(ワーカーは処理せず、状態も書き換えない)。 */
val Bookmark.isProcessingCancelled: Boolean
    get() = aiStatus == AiStatus.FAILED && processing.failureReason == ProcessingFailureReason.CANCELLED

/**
 * 処理の状態の詳細([AiStatus] を補う)。日時はすべて epoch ミリ秒で、分からなければ null。
 *
 * - 処理待ちにした([AiStatus.PENDING]): [queuedAt] を入れ、ほかは空にする
 * - 段を始めた([AiStatus.PROCESSING]): [stage]・[stageStartedAt] を入れる。[startedAt] は最初の段の開始日時
 * - 段を終え、次の段・再試行を待つ([AiStatus.PENDING]): [stage]・[stageStartedAt] を空にする([startedAt] は残す)
 * - 終えた(完了・失敗・準備待ち): [finishedAt] を入れ、失敗・準備待ちなら [failureReason] も入れる
 *
 * 端末の中の一時的な状態のため、バックアップには含めない。
 */
data class ProcessingInfo(
    /** 処理中の段。処理中でなければ null */
    val stage: ProcessingStage? = null,
    /** 処理を頼んだ(保存・再処理・積み直し)日時 */
    val queuedAt: Long? = null,
    /** 最初の段を始めた日時 */
    val startedAt: Long? = null,
    /** 今の段を始めた日時 */
    val stageStartedAt: Long? = null,
    /** 処理を終えた(完了・失敗・準備待ちになった)日時 */
    val finishedAt: Long? = null,
    /** 失敗・準備待ちの理由。再試行を待っている間は、直前の失敗の理由 */
    val failureReason: ProcessingFailureReason? = null
)
