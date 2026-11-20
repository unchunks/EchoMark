# 動画のオフライン保存

動画のブックマークを、通信できなくても見られるように端末へ保存する。
**動画のファイルそのものは、ダウンロードが認められているものだけを保存する。** それ以外の動画(YouTube など)は、
映像・音声を保存せず、字幕・要約・サムネイルを保存する。

## 保存するもの

| 動画の種類 | 保存するもの | 根拠 |
| --- | --- | --- |
| YouTube | 字幕(取れたとき)・要約・サムネイル。**映像・音声は保存しない** | 下の「YouTube」 |
| PeerTube | 字幕(公開の API から)・要約・サムネイル | 下の「PeerTube」 |
| そのほかの動画サイト(Vimeo・ニコニコ動画・TikTok・Twitch・Dailymotion) | 説明文(ページから取れたもの)・要約・サムネイル。字幕は取らない | 認められた字幕の取り方を確かめられていない |

字幕と要約は、ブックマークの本文(`bookmarks.content` の「字幕:」以下)と要約(`bookmarks.summary`)に入る。
DB の中にあるので、もともと通信せずに読める。オフライン保存の作業では、本文に字幕が無ければリンク先を取り直して入れ、
要約が無い・字幕を新しく入れたときは要約だけを作り直す(手で直した要約は置き換えない)。
サムネイルは端末に保存する(`offline_videos.thumbnailPath`)。

## サイトごとの判断

調べたのは 2026-11-20。規約は変わるので、サイトを足すとき・見直すときは、その時点の規約と API の説明を読み直す。

### YouTube

- [利用規約](https://www.youtube.com/t/terms)は、サービスが明示的に認めた場合か、YouTube と権利者の書面の許可がある場合を除き、
  コンテンツへのアクセス・複製・**ダウンロード**などを禁じている。映像・音声のストリームはダウンロードしない
  (player API の応答に入っている `streamingData` などの配信の URL は使わない)。
- 字幕: [YouTube Data API の captions.download](https://developers.google.com/youtube/v3/docs/captions/download) は
  「動画を編集する権限」が要り(OAuth)、他人の動画の字幕を取る公式の手段は無い。
  アプリは以前から、YouTube のアプリが視聴者に見せる字幕を、部品の `YouTubeTranscriptFetcher`(InnerTube の player API を
  Android のアプリとして呼ぶ)で取っている。公開された API ではなく、規約の上では「明示的に認められた方法」と言い切れない。
  **この PR では取り方を変えていない**(新しい取り方を足していない)。取り方を続けるかは利用者・開発者の判断に残す。
- サムネイル: ページの OG 画像(`i.ytimg.com`)を、ブックマークの一覧・詳細に出すのと同じ画像として1枚保存する。

### PeerTube

- 動画の情報(`GET /api/v1/videos/{id}`)と字幕(`GET /api/v1/videos/{id}/captions`、ファイルは `fileUrl`。古い版は `captionPath`)は、
  サーバーがだれにでも見せている公開の REST API([API の説明](https://docs.joinpeertube.org/api-rest-reference/api.html))。
  認証は要らない。
- 動画のファイルは、投稿者が動画ごとに「ダウンロードを許す」を選べる(`downloadEnabled`。
  [投稿の説明](https://docs.joinpeertube.org/use/create-upload-video))。ファイルを保存するのは、これが true のものだけにする(次の PR)。
- サーバーごとにホストが違うため、動画ページのパスの形(`/w/<ID>`・`/videos/watch/<ID>`・`/videos/embed/<ID>`)で見分け、
  同じサーバーの API が PeerTube の答え(`uuid` と `name`)を返すかで確かめる(`PeerTubeUrls`・`PeerTubeFetcher`)。
  API を呼ぶ先は、ブックマークしたページと同じサーバーだけ。

### 動画のファイルを保存する候補(次の PR で扱う)

- **動画のファイルへの直接のリンク**(Content-Type が `video/*` の mp4・webm など): リンクの先がファイルそのもので、
  開けばブラウザでもダウンロードされるもの。今も、100MB までなら本文の取得のときに添付ファイルとして保存している。
- **PeerTube で `downloadEnabled` が true の動画**: 上のとおり、投稿者がダウンロードを認めたもの。
- **Wikimedia Commons**: [再利用の説明](https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia)が
  ファイルのダウンロードと利用を認めている(ファイルごとのライセンスの条件に従う)。API への User-Agent に連絡先を求める
  [方針](https://foundation.wikimedia.org/wiki/Policy:User-Agent_policy)があるため、入れるかは次の PR で決める。
- **Internet Archive**: 利用規約を読めていないため(2026-11-20 に取得できなかった)、**入れない**。読めて、ダウンロードが
  認められていると確かめられたら足す。
- 入れないもの: Vimeo(ダウンロードは投稿者が認めたものだけで、確かめるには認証が要る)・ニコニコ動画・TikTok・Twitch・Dailymotion
  (ダウンロードを認めた公開の手段を確かめられていない)。

## 保存先・削除・バックアップ

- 記録は `offline_videos` テーブル(v32。ブックマーク1件に1行)。ブックマークの削除を「元に戻す」で取り消せるよう外部キーにはせず、
  ブックマークが無くなった行は起動時の掃除(`UnusedAttachmentCleaner`)で消す。
- サムネイルは添付ファイルと同じ置き場所(`filesDir/attachments/`)に置く。参照されなくなったものは、ほかの添付ファイルと同じく
  猶予(1日)の後に消える。「すべてのデータを削除」で、記録・サムネイル・動いている保存の作業を消す。
- バックアップには入れない(`BackupCoverage` の `offline_videos`)。端末ごとの保存で、サムネイルはリンク先から取り直せるため。
  字幕と要約はブックマークの本文・要約なので、バックアップに入る。

## 通信とオフラインモード

- 保存は WorkManager(`VideoOfflineWorker`)で行う。通信できるときだけ動く。字幕とサムネイルは小さいので、
  従量課金の接続でも動く(動画のファイルの保存は、次の PR で Wi-Fi のときだけにする設定を足す)。
- 通信はすべて本文の取得と同じ OkHttp の接続(`UrlContentFetcher`。ローカルネットワークに接続しない・オフラインモードで断る)。
- オフラインモードの間は保存を始めず、画面で理由を出す。途中でオンにされたら、何も書かずに止める。
