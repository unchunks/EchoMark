---
name: echomark-dev
description: EchoMark(Android のブックマーク × AI アプリ)で機能の追加・不具合の修正・リファクタリングをするときの手順と決まり。ブランチと PR の運用、コミットメッセージの書き方、DB のスキーマ変更、AI(端末内・クラウド)まわりの注意、テストと確認の仕方をまとめている。EchoMark のコードを変える作業を始める前に必ず読むこと。ブランチを切る・コミットする・PR を作る・サブエージェントに実装を任せるときにも使う。
---

# EchoMark 開発ガイド

EchoMark は「保存したものを AI が要約・タグ付けし、あとから意味で引き出せる」Android アプリ。
Kotlin / Jetpack Compose / Hilt / Room / ObjectBox(ベクトル検索)/ WorkManager / LiteRT-LM(端末内 LLM)/
Claude・Gemini・OpenAI の API で作っている。LLM・埋め込み・文字起こし・暗号化保存などの汎用部分は
非公開の部品リポジトリ `unchunks/components` に切り出してあり、`unchunks-*` の依存として使う(`docs/components.md`)。
軸は **AI ブックマーク管理**で、**端末内 AI だけでも完結する**ことを目指す。

## 1. 作業を始める前に

1. `git fetch origin develop` して、**`origin/develop` からブランチを切る**。`main` からは切らない。
   - 名前は `feature/…`(機能)・`fix/…`(不具合)・`perf/…`(高速化)・`chore/…`(整備)・`refactor/…`
2. 関係するドキュメントを読む
   - DB の列・テーブルを触る → `docs/database-migrations.md`(必読)
   - 画面を作る・変える → `docs/screenshot-testing.md`
   - 部品(API クライアント・埋め込み・暗号化保存など)を触る → `docs/components.md`
   - 埋め込み・検索のしきい値 → `docs/embedding-evaluation.md`
3. ビルドできる環境かを確かめる。ビルドには **非公開の部品リポジトリ `unchunks/components`** と Android SDK が要る
   (`settings.gradle.kts` が親のディレクトリをたどって `components/android/` を探す)。
   - 手元で `./gradlew` が動かない環境(クラウドのセッションなど)では、**CI の結果で確かめる**(後述)。
     ビルドしていないことは PR に必ず書く。

## 2. ブランチと PR

- PR のマージ先は **`develop`**。`develop` → `main` はリリースのときだけ。
- **PR はスタックしない**(PR のベースを別の機能ブランチにしない)。以前、スタックした PR が `develop` ではなく
  下のブランチにマージされ、変更が `develop` に入らない事故があった。依存する変更は、先の PR がマージされてから
  `develop` から切り直す。
- 1つの PR は1つのまとまり(番号付きの案ならいくつかまとめてよい)。関係ない変更を混ぜない。
- `develop` が進んだら **`develop` をマージして取り込む**(他人のブランチは rebase・force-push しない)。
- 並行で進めるときは、触るファイルが重ならないように分ける。**DB のスキーマを変える PR は同時に1本だけ**にする
  (版の番号とスキーマ JSON がぶつかる)。

### PR の本文

日本語で、次の見出しに沿って書く。

```markdown
## 概要
何のための変更か(症状・目的)。

## 変更内容
- 箇条書き。DB・バックアップの版を上げたら「DB v20 → v21、バックアップ v12 → v13」と書く。変えていなければ「スキーマの変更はなし」

## 未検証・要確認
- ビルド・テストを実行していなければ、そう書く。API の名前を記憶で書いた所、実機でしか確かめられない所も書く

## 確認方法
- 実行するコマンドと、実機で確かめる操作
```

## 3. コミットメッセージ

最近のコミットに合わせる(`git log origin/develop --no-merges -5` で確かめる)。

- 1行目: **何ができるようになるか / 何が直るかを日本語の文で**書く(`feat:` などの接頭辞は付けない)。
  例: 「読んだかどうかを管理し、読む時間の目安と「次に読む」のおすすめを出す」
- 本文: 箇条書きで、利用者から見た変化と、仕組みの要点。DB・バックアップの版の変更、「全データを削除」での扱い、
  「スキーマの変更はなし」なども書く。
- 案の番号に対応するなら 1行目の末尾に `(#73, #79)` のように付ける。
- 末尾にはセッションから指定された Co-Authored-By などの行を付ける。

## 4. DB のスキーマを変えるとき

手順の正本は `docs/database-migrations.md`。特に守ること:

- `AppDatabase.VERSION` を 1 上げ、`Migrations.kt` に `MIGRATION_<旧>_<新>` を書いて `ALL_MIGRATIONS` に足す。
- **ビルドが書き出す `app/schemas/.../<新>.json` をコミットする**。CI はスキーマ JSON と ObjectBox のモデルの
  コミット漏れで失敗する。ビルドできない環境ではこの JSON を作れないため、スキーマ変更の PR は
  ビルドできる環境で仕上げるか、PR に「スキーマ JSON 未生成」と明記して手元での生成を頼む。手で書かない。
- `MigrationTest` に既存の行がどうなるかのテストを足す。
- 利用者のデータの列なら **バックアップ(`data/backup/BackupJson.kt`)にも足し、`CURRENT_VERSION` を上げる**。
- 利用者のデータを足したら、**「全データを削除」**(設定を残す場合を含む)で消えるかを決めて実装する。
- `fallbackToDestructiveMigration*` を足さない。公開済みの版のマイグレーションとスキーマ JSON は書き換えない。

## 5. コードの書き方

- **コメント・KDoc は日本語**。「なぜそうするか」を書く(既存のコードと同じくらいの量)。
- 層: `ui/`(画面と ViewModel)・`domain/`(モデル・リポジトリのインターフェース・純粋なロジック)・
  `data/`(実装・DB・API・AI)・`worker/`(WorkManager)・`di/`(Hilt)。
- スレッド: `DispatcherProvider`(`com.unchunks.components.core`)を注入し、`withContext(dispatcherProvider.io)` などで切り替える。`Dispatchers.*` を直に書かない。
- ログは Timber。
- 画面は `XxxScreen(viewModel)` と、状態とコールバックだけを受け取る `XxxContent(uiState, …)` に分ける
  (スクリーンショットテストで描画できるように)。サンプルデータは `ui/components/PreviewSamples.kt`。
- 画面の文言は日本語。利用者向けにやさしく、具体的に(設定のどこを開けばよいかまで書く)。
- 単体テストは JVM(Robolectric 可)で書く。フェイクは `app/src/test/.../testing/`(`FakeRepositories.kt`・`FakeAi.kt` など)
  を使い、インターフェースを変えたらフェイクも直す。
- 部品(`unchunks-*` の依存)にあるものを直すときは components リポジトリ側で直す。アプリに複製しない。

## 6. AI まわりの決まり

- **プロンプトは `data/ai/AiPrompts.kt` に集める**。変えたら `AiPromptsTest` も直す。
- AI 処理は `BookmarkWorkScheduler` が「本文取得 → 中身の取り出し → AI 処理」の WorkManager のチェーンで登録する。
  各工程は冪等にする(リトライで全体をやり直さない)。状態は `AiStatus` で表す。
- **バックグラウンドのスコープ(`CoroutineScope(SupervisorJob() + …)`)には `CoroutineExceptionHandler` を付け、
  ネイティブを呼ぶ所は `Throwable` まで捕まえる**。以前、端末内 AI の GPU の初期化で `UnsatisfiedLinkError`(Error)が
  ハンドラの無いスコープに抜け、AI の再処理でアプリが落ちた。
- 端末内 AI の GPU は設定でオンにしたときだけ使う(既定は CPU)。GPU で落ちたモデルは自動で CPU に戻す仕組み
  (部品の `llm.ondevice.BackendSelector`)を壊さない。
- 端末内 LLM と埋め込みモデルを同時に読み込まない(メモリが一気に増えて強制終了される)。
- **クラウド API の既定モデルは利用者が決めること**。勝手に変えない(プリセットを足すのはよい)。
  要約だけ Claude Haiku 5.5 が既定、タグ付け・チャットは Opus 5.5。
- Claude API を呼ぶコードを書く・変えるときは、先に `claude-api` スキルで現行のモデル ID と API の形
  (thinking・effort・fallbacks)を確かめる。記憶で書かない。
- SDK・ライブラリの API 名を記憶で書いたときは、PR の「未検証・要確認」に書く。

## 7. 確かめ方

push の前に、できる範囲で次を通す。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# DB を触ったとき
./gradlew :app:testDebugUnitTest --tests "com.unchunks.echomark.data.local.*"
```

- ビルドできない環境では、変えた・消したシンボルの呼び出し元を `grep` ですべて確かめ、import・Hilt のバインディング・
  フェイクの追従を目で確かめる。そのうえで PR の CI(ビルド・単体テスト・Lint の3ジョブ)の結果を見て、失敗したら直す。
- CI はリポジトリの secret `COMPONENTS_REPO_TOKEN` で部品を取得する。部品の取得で失敗したら、コードではなく設定の問題として報告する。
- 実機でしか分からないこと(GPU・通知・フォアグラウンド・権限)は、確認方法に実機での操作を書く。
- 処理時間は `adb logcat -s AiPerf` で段ごとに見られる。

## 8. サブエージェントに任せるとき

依頼文に次を必ず入れる(サブエージェントはこの会話を知らない)。

- 自分の作業ツリーの中だけで作業し、ほかのチェックアウトのブランチを切り替えないこと
- `origin/develop` から切るブランチ名と、push 先(`git push -u origin <ブランチ>`)
- このスキル(`.claude/skills/echomark-dev/SKILL.md`)を最初に読むこと
- 触ってよいファイル・触ってはいけないファイル(並行の作業とぶつからないように)
- DB のスキーマを変えてよいか、変えるなら版の番号
- PR は作らずに報告だけ返すか、PR まで作るか
- ビルドできない環境なら、未検証の項目を報告に列挙すること
