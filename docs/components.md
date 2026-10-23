# 再利用する部品(components)

EchoMark のうち、ほかのアプリでも使える仕組み(LLM の API クライアント・端末内の埋め込み・秘密情報の暗号化保存・
音声の文字起こし・PDF と画像の文字の読み取り・Compose の部品など)は、隣のリポジトリ `../components` に切り出してある。
部品の一覧と中身は `../components/README.md`。

## ビルドに必要なもの

EchoMark は部品を composite build で読み込むため、EchoMark だけではビルドできない。次のように並べて置く。

```
AndroidStudio/
  EchoMark/      ← このリポジトリ
  components/    ← 部品のリポジトリ
```

- `settings.gradle.kts` が親のディレクトリをたどって `components/` を探す(`.claude/worktrees/` の下の作業ツリーからも見つかる)
- 別の場所に置くときは、gradle.properties の `unchunks.componentsDir`(EchoMark からの相対パスか絶対パス)か、
  環境変数 `UNCHUNKS_COMPONENTS_DIR` で指定する
- 部品のビルドにも Android SDK が要る(`components/local.properties` の `sdk.dir` か `ANDROID_HOME`)

## 使い方

- 依存は `gradle/libs.versions.toml` の `unchunks-*`(座標 `com.unchunks.components:<部品>`)。Gradle が components のプロジェクトに置き換える
- SDK・Java の版、release の R8 と署名、JVM テストの設定は、部品の規約プラグイン `unchunks.android.application` が決める
  (`components/build-logic`)。署名の環境変数の接頭辞は gradle.properties の `unchunks.signing.envPrefix`(`ECHOMARK`)
- 部品を直したら、部品のテスト(`components` で `./gradlew build`)と EchoMark のテストの両方を通す

## アプリに残したもの

ブックマーク・タグ・チャットのデータと画面、プロンプト、Room・ObjectBox、ワーカー、設定画面の文言(エラーに添える設定画面の場所、
モデルの選択肢の表示名)、同梱する埋め込みモデルと取り込めるモデルの一覧、Keystore の鍵の名前と API キーの保存名は、EchoMark に残した。
