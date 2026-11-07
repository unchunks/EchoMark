# オフラインモード(通信の経路の一覧と守り方)

設定の「通信」→「オフラインモード」をオンにすると、アプリは一切ネットワークに接続しない。
通信してよいかは `data/network/NetworkGate.kt` の1か所で決め、すべての経路がそこを通る。

## 守り方

- **OkHttp を使う経路**: 共通の `OkHttpClient`(`di/NetworkModule.kt`)に `OfflineModeInterceptor` を付けている。
  各所の client は共通の client から `newBuilder()` で作るため、インターセプターを引き継ぐ(名前解決・接続より前に断る)。
  オンにした瞬間に、共通の `Dispatcher` を通して実行中の通信も取り消す(`OfflineModeController`)。
- **OkHttp を通らない経路**: 呼ぶ前に `NetworkGate.ensureOnline()` / `isOffline()` で確かめる。
- **抜け道を作らない**: `NetworkPolicyCoverageTest` が main のソースを調べ、`OkHttpClient.Builder()` で新しく作る・
  WebView・DownloadManager・地図・クラウド AI のクライアント・`HttpURLConnection` などを、決めたファイル以外で使うと失敗する。
  新しい経路を足すときは、オフラインモードで止まるようにしてからテストの一覧に理由とともに足す。

## 通信の経路

| 経路 | 仕組み | オフラインモードでの扱い |
| --- | --- | --- |
| URL の本文の取得(`UrlContentFetcher`) | 共通の client から作った OkHttp | インターセプターで断る。処理は積まずに処理待ちのまま残す |
| GitHub の API・raw ファイル(`GitHubRepoFetcher`) | 本文の取得と同じ client | 同上 |
| YouTube の字幕(部品の `YouTubeTranscriptFetcher`) | 本文の取得と同じ client | 同上 |
| フィードの確認・購読の追加(`FeedFetcher`・`FeedDiscovery`) | 共通の client から作った OkHttp | 夜間の確認は積まない・ワーカーも動かない。途中でオンにされたら失敗に数えずにやめる |
| リンク切れの確認(`LinkProber`) | 共通の client から作った OkHttp | 夜間の確認は積まない。途中でオンにされたら記録せずにやめる(`NetworkStatus` も通信できない扱い) |
| 資料探し(部品の Web 検索: arXiv・はてなブックマーク・Wikipedia・YouTube) | Hilt で渡す共通の client | 画面で理由を出して検索しない |
| リンク先の画像(Coil) | 共通の client(`EchoMarkApplication`) | インターセプターで断る(キャッシュ済みの画像は出る) |
| オフラインコピーの作成(`PageSnapshotter`) | WebView | 読み込む前と、ページの中の読み込みのたびに確かめる |
| オフラインコピーの表示(`SnapshotViewer`) | WebView | もともと保存したファイル以外を読み込まない(通信しない) |
| おすすめのモデルのダウンロード(Hugging Face) | DownloadManager(端末の側で通信) | 始めない。オンにしたら、ダウンロード中のものをやめる |
| クラウド AI(Claude・Gemini・OpenAI。接続テストを含む) | Claude は SDK の自前の OkHttp、ほかは共通の client | `OfflineGuardedApiLlmClient` が呼ぶ前に断る。処理は処理待ちのまま残す(今は `CLOUD_AI_ENABLED = false`) |
| クラウドの文字起こし(OpenAI・Gemini) | 共通の client | 端末内の文字起こしを使う |
| 場所の画面の地図の背景(MapLibre + OpenFreeMap) | MapLibre の HTTP を共通の client から作った client に差し替え | 地図を作らず、分布図にして理由を出す |

### 処理待ちの扱い

- オフラインモードの間は、通信の要る処理(本文の取得・クラウド API の AI 処理)を WorkManager に積まず、処理待ちのまま残す
  (`BookmarkWorkScheduler`)。端末の中だけで済む処理(メモ・ファイルの中身の取り出し・端末内 AI)はそのまま動く。
- 積んだ後にオンにされたものは、ワーカーが通信の前に止める(`UrlFetchWorker`・`BookmarkAiProcessingWorker` は
  失敗にも再試行にもせず、処理待ちのまま残す)。
- オフにしたら、処理待ちのものを積み直す(`OfflineModeController` → `enqueueWaitingProcessing`)。
  夜間にまとめて処理する設定なら、積まずに夜を待つ。
- 「処理の状況」の画面に、通信の要る処理が待っていることを出す。

### オフラインモードで止められないもの(アプリの外の通信)

- **ブラウザ・地図アプリ・共有で開く**: 利用者が押したときに、ほかのアプリが通信する(アプリ自身は通信しない)。
- **画像のラベル付け(ML Kit、Google Play 開発者サービス版)**: モデルは Play 開発者サービス(別のプロセス)が取得する。
- **現在地**: `LocationManager` の fused / network の位置は、端末の位置情報サービスが求める。
- **Android の自動バックアップ**: 端末(システム)が行う。

### ML Kit の利用状況の送信(Google Data Transport)

文字認識(`com.google.mlkit:text-recognition-japanese`)などの ML Kit は、アプリのプロセスの中で、Google Data Transport
(`HttpURLConnection`)を使って利用状況を Google に送る。OkHttp を通らず、送る時期もライブラリが決めるため、
オフラインモードで止められない。そこで、送信先(CCT のバックエンド)の登録をマニフェストで外し、オフラインモードに
関係なく常に送らないようにしている(`AndroidManifest.xml` の `TransportBackendDiscovery`)。
アプリの機能(文字認識・ラベル付け)は送信と関係なく動く。
