# 再利用する部品(components)

EchoMark のうち、ほかのアプリでも使える仕組み(LLM の API クライアント・端末内の埋め込み・秘密情報の暗号化保存・オフラインモードの仕組み・
音声の文字起こし・WAV の読み書き・PDF と画像の文字の読み取り・端末内 AI のエンジン・資料の検索・RSS と Atom のフィードの読み取り・字幕のファイルの読み取り・
zip の読み書き・アプリのロック・件数のグラフ・Brave Search API のクライアント・続きから落とせるダウンロード・日本語の読み上げの整え・
Compose の部品など)は、
別のリポジトリ components(非公開の unchunks/components)の `android/` に切り出してある。部品の一覧と中身は `components/android/README.md`。

## ビルドに必要なもの

EchoMark は部品を composite build で読み込むため、EchoMark だけではビルドできない(部品のコピーは持たない)。次のように置く。

```
unchunks/
  AndroidStudio/
    EchoMark/      ← このリポジトリ
  components/      ← git clone git@github.com:unchunks/components.git
    android/       ← 部品の Gradle のビルド
```

- `settings.gradle.kts` が親のディレクトリをたどって `components/android/` を探す(`.claude/worktrees/` の下の作業ツリーからも見つかる)。
  見つからなければ、どこに置けばよいかを示すエラーで止まる
- 別の場所に置くときは、gradle.properties の `unchunks.componentsDir`(EchoMark からの相対パスか絶対パス)か、
  環境変数 `UNCHUNKS_COMPONENTS_DIR` で指定する
- 部品のビルドにも Android SDK が要る(`components/android/local.properties` の `sdk.dir` か `ANDROID_HOME`)

## 使い方

- 依存は `gradle/libs.versions.toml` の `unchunks-*`(座標 `com.unchunks.components:<部品>`)。Gradle が components のプロジェクトに置き換える
- SDK・Java の版、release の R8 と署名、JVM テストの設定は、部品の規約プラグイン `unchunks.android.application` が決める
  (`components/android/build-logic`)。署名の環境変数の接頭辞は gradle.properties の `unchunks.signing.envPrefix`(`ECHOMARK`)
- 部品を直したら、部品のテスト(`components/android` で `./gradlew build`)と EchoMark のテストの両方を通す

## アプリに残したもの

ブックマーク・タグ・チャットのデータと画面、プロンプト、Room・ObjectBox、ワーカー、設定画面の文言(エラーに添える設定画面の場所、
モデルの選択肢の表示名)、同梱する埋め込みモデルと取り込めるモデルの一覧、Keystore の鍵の名前と API キーの保存名、資料を探すときに名乗る User-Agent
(`di/NetworkModule.kt`)、資料を AI に選ばせる処理(`data/discover/ResourceDiscovery.kt` など)は、EchoMark に残した。
オフラインモードの値を設定から読む処理(`data/network/NetworkGate.kt` の `SettingsNetworkGate`)と、切り替えたときに通信・ダウンロードを止めて
処理を積み直す処理(`OfflineModeController`)、フィードを読んだあとの購読の種類(`SubscriptionType`)と購読の確認(`FeedChecker`)、
ポッドキャストの台本と音声を作る処理(`PodcastGenerator`)も、EchoMark に残した。
アプリのロックの設定と選択肢の表示名(`domain/lock/AppLock.kt`)、ロック中の画面とロックをオンにする前の確かめ方(`ui/lock/AppLockGate.kt`)、
バックアップの形式(`backup.json` と `attachments/` の名前の決まり、`BackupArchive`・`BackupJson`・`BackupCoverage`)も、EchoMark に残した。
Web 検索のキーの保存先とキーが無いときの知らせ・AI に渡す抜粋の整え(`data/websearch/BraveWebSearchClient.kt`・`domain/websearch/WebSourceText.kt`)、
PeerTube の API と動画の画質の選び方・失敗の理由(`data/video/VideoFileDownloader.kt`)、読み上げで入れてある読みのうちアプリの名前
(`domain/podcast/AppReadings.kt`)も、EchoMark に残した。
