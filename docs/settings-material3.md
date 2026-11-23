# EchoMark 設定画面の Material 3 仕様

設定タブでは、AI の準備状況を確認し、表示や通知をその場で変更できる。データ管理、アプリ情報、全データ削除は別の見出しから探せる。画面は日本語を前提とし、読み取り順は AI → 表示 → 通知 → 処理 → 要約 → 通信 → ロック → データ管理 → このアプリについて → データの削除。

## モバイル画面の監査

| 優先度 | 変更前の所見 | 実装した修正 |
| --- | --- | --- |
| 高 | 「すべてのデータを削除」がバックアップとストレージの並びにあり、誤操作しやすい。 | 最後に独立した「データの削除」を配置。削除範囲と API キーの初期化を確認してから、不可逆の確定操作に進む。 |
| 高 | 時刻は汎用の `AlertDialog` に時計盤を埋め込んでおり、入力方法が時計盤に限られていた。曜日の選択肢にもスクロールがなかった。 | `TimePickerDialog` に時計盤とキーボード入力を用意。画面が低い場合と文字拡大時は入力型を使う。テーマと曜日の選択肢は縦スクロールできる。 |
| 中 | AI の準備状態は末尾アイコンとその読み上げに依存し、操作先を示すアイコンにも一貫性がなかった。 | AI 設定を主となる遷移先として、準備状態、現在の構成、遷移用の矢印を一つのカードにまとめた。状態は「準備完了」または「設定が必要」と表示する。 |
| 中 | バックアップ対象外の情報が操作行の後にあり、ファイル選択を開く前に読み飛ばしやすい。通知権限の警告にもライブリージョンがなかった。 | バックアップの補足を操作行の前へ移動。通知の警告はスイッチ直後に表示し、読み上げと端末の通知設定へのリンクを提供する。 |
| 中 | 設定の本文は幅広のウィンドウでも1列で、アプリバーの大きさも共通だった。 | ミディアム幅では小型アプリバーと読みやすい本文幅を使い、拡張幅では設定と管理を並べて表示する。 |
| 中 | 320 dp・文字2倍で「ブックマーク」が折り返すと、下部ナビゲーションのアイコンの高さが揃わない。 | 幅440 dp 未満・文字倍率1.5倍以上で、すべての項目に2行分のラベル領域を確保する。 |

行全体を操作できる `Switch`、相互排他的な選択肢の `RadioButton`、セクション見出しの `heading()`、親画面の `NavigationBar` と `NavigationRail` は適切に使われていた。

## 適応型レイアウト

幅のクラスは、設定本文の幅を測る前にアプリのウィンドウから取得する。ナビゲーションの選択と設定本文の分割に、共通の `AdaptiveLayout` を使う。開始側は日本語表示では左。幅と高さの判定は Android のウィンドウサイズクラスに従う。 [ウィンドウサイズクラス](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)

| ウィンドウ幅 | アプリナビゲーション | 設定画面 |
| --- | --- | --- |
| コンパクト、600 dp 未満 | 下部の `NavigationBar` | 1列。本文左右の余白は16 dp。高さがある場合は折りたためる `LargeTopAppBar`。 |
| ミディアム、600–839 dp | 開始側の `NavigationRail` | 固定の小型 `TopAppBar` と中央寄せの1列。左右24 dp の余白を含め、リスト幅は最大600 dp。 |
| 拡張、840 dp 以上 | 開始側の `NavigationRail` | 高さ480 dp 以上では2ペイン。開始側は AI・表示・通知・処理・要約・通信・ロック、終了側はデータ管理・アプリ情報・削除。各ペインのリスト幅は最大600 dp。左右24 dp の余白とペイン間の区切り線を使う。 |

- 高さが480 dp 未満なら小型のアプリバーを優先する。分離する縦ヒンジがある場合、共通シェルの2ペイン規則を優先し、Adaptive ライブラリの除外領域に本文が重ならないようにする。 [折りたたみ対応](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)
- 2ペインのスクロールは独立。1列と2ペインの切り替えでは、それぞれの配置で最後に見ていた位置を保存する。表示中のダイアログと削除オプションはレイアウトの外で保存する。
- システムバーと画面端のインセットをアプリシェルとアプリバーで受け持つ。子リストでは消費済みのインセットを重ねない。

## セマンティックなトークン

アプリのライト・ダーク配色は `ui/theme/Theme.kt` が `ColorScheme` に割り当てる。Android のダイナミックカラーも同じロールで参照する。共通テーマで `primaryFixed`、`secondaryFixed`、`tertiaryFixed` と対応するロールをブランド配色に割り当て、固定ロールはライト・ダーク共通とする。新しいアプリ画面の地色は `surface` を使う。 [Compose の Material 3 テーマ](https://developer.android.com/develop/ui/compose/designsystems/material3)

| 用途 | コンテナ / 前景 | ブランド配色でのコントラスト比（ライト / ダーク） |
| --- | --- | --- |
| リスト本文 | `surface` / `onSurface` | 16.30 / 14.35 |
| 補足文・通常アイコン | `surface` / `onSurfaceVariant` | 8.88 / 10.89 |
| セクション見出し | `surface` / `primary` | 6.34 / 10.90 |
| AI 設定 | `primaryContainer` / `onPrimaryContainer` | 7.41 / 7.41 |
| バックアップの補足 | `surfaceContainerLow` / `onSurfaceVariant` | 8.43 / 10.06 |
| 通知権限の警告と回復アクション | `errorContainer` / `onErrorContainer` | 7.24 / 7.24 |
| 削除の行 | `surface` / `error`（名称とアイコン） | 6.14 / 10.93 |
| 削除の最終確定 | `error` / `onError` | 6.46 / 7.72 |
| ペインの境界 | `outlineVariant` の区切り線 | 本文と見出しがペインの意味を伝える |

コントラスト比はブランド配色の不透明色から算出。動的配色、フォーカスリング、状態レイヤーは対象端末で確認する。文字の目標は通常4.5:1、意味のある非テキスト要素は3:1。

書体は共通の `Typography` を使う。ロールは、大型バーが `headlineMedium`、小型バーと AI カードのタイトルが `titleLarge`、セクション見出しと AI の準備状態が `titleSmall`、通常行が `bodyLarge`、補足が `bodyMedium`。長文は日本語の文節で折り返し、行数を固定しない。

AI のカードは `shapes.extraLarge`（28 dp）とトーナルなコンテナ色を使う。補足・警告は `shapes.medium`（16 dp）の `Surface`。リスト行は背景面に連続して置く。表現の強い配色と形状は、最初に確認する AI の状態へ集約する。 [Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)

### デザインの切り替え

「表示」の「デザイン」で、アプリ全体の配色・書体・形状を切り替える。テーマ（明暗）とダイナミックカラーとは独立した設定で、既定はクラシック。ダイナミックカラーがオンの間は配色だけを壁紙に合わせ、書体と形状は選んだデザインのものを使う。トークンは `ui/theme/DesignStyles.kt`、トークンで表せない部品の違いは `EchoMarkDesign` にまとめる。

| デザイン | 配色 | 書体 | 形状（small / medium / extraLarge） | 部品の違い |
| --- | --- | --- | --- | --- |
| クラシック | ブランドのインディゴとティール | 共通の `Typography` | 8 / 16 / 28 dp | なし |
| エコー | 彩度を上げたインディゴとティール。地色とコンテナの段差を広げる | 見出しとタイトルを SemiBold、headline を拡大 | 12 / 24 / 32 dp | 一覧の「今日の再発見」を `primaryContainer` の吹き出し形の帯に載せる。カードの AI 要約を `surfaceContainerLowest` の面とティールの印で強調する。ボトムバーは画面の端から離した角丸 32dp の浮いたバー（置き場所は通常の bottomBar のままなので、インセットと中身の余白の扱いは変わらない） |
| ペーパー | 紙色の地、墨色の文字、黄土色のアクセント | display・headline・titleLarge・titleMedium を端末の明朝体 | 4 / 6 / 12 dp | カードは地色に `outlineVariant` の罫線。ナビゲーションバーは地色と上端の罫線 |

どのデザインも同じ `MaterialTheme` の呼び出しで値だけを替えるため、切り替えても画面の状態（ナビゲーションの履歴、スクロール位置）は保たれる（`EchoMarkThemeTest`）。Expressive の動き（`MotionScheme`、`MaterialExpressiveTheme`）は material3 1.4 では公開されていないため、公開後にエコーへ加える。ペーパーの明朝体は端末の serif（日本語は Noto Serif CJK）を使い、フォントを同梱しない。描画例は `DesignStyleScreenshotTest`（`design_echo_*`、`design_paper_*`、`design_intro_library_*`、`settings_dialog_design_*`）。

各デザインの配色は、文字を載せる組み合わせ（地色と本文・補足・見出し、各コンテナと対応する文字色）がライト・ダークとも 4.5:1 以上であることを `DesignStyleContrastTest` で確かめる。

クラシックのままの人には、一覧の先頭に一度だけ「新しいデザインを選べます」を出す。「デザインを選ぶ」でこのダイアログを開き、選ぶか「閉じる」で以後は出さない（`DesignIntroViewModel`）。設定からデザインを選んだ人にも以後は出さない。検索中・絞り込み中・複数選択中と、一覧が空のときは出さない。

ホーム画面の「再発見」ウィジェットも選んだデザインに合わせる（`RediscoverWidgetTheme`）。エコーはウィジェット全体を一覧の「今日の再発見」と同じ primaryContainer の帯にして大きな角丸の行を載せ、ペーパーは紙色の地に角の小さい行を並べてタイトルを明朝体にする。デザインを変えるとウィジェットも描き直す（`RediscoverWidgetUpdater`）。描画例は `RediscoverWidgetScreenshotTest`（`rediscover_widget_large_echo_*`、`rediscover_widget_large_paper_*`）。

## コンポーネントと状態

| 操作 | Compose Material 3 のバリエーションと挙動 |
| --- | --- |
| AI の設定 | クリック可能な Filled `Card`。用途ごとの状態を本文で表示し、既存の AI 設定画面へ遷移する。 |
| 遷移やモーダルを開く設定行 | 1〜複数行の `ListItem`。行全体を操作対象として `Role.Button` を付ける。ストレージとバージョンは読み取り専用の `ListItem`。 |
| ダイナミックカラー・再発見通知 | 行全体に `toggleable` と `Role.Switch`。末尾の `Switch` は同じ状態を描画する。変更は即時。通知の有効化に権限が必要なら OS の許可結果を待つ。 |
| テーマ・曜日 | `AlertDialog` と単一選択の `RadioButton`。選択行を押すと適用して閉じる。キャンセルは `TextButton`。 |
| デザイン | テーマと同じ単一選択の `AlertDialog`。各行に名前・説明と、そのデザインの地色・見出し・角丸の縮図（読み上げなし）を添える。ダイナミックカラーがオンなら、配色は壁紙に合わせることを先に表示する。 |
| 時刻 | `TimePickerDialog`、24時間制の `TimePicker` / `TimeInput`、入力方式を切り替える名前付き `IconButton`、保存・キャンセルの `TextButton`。高さ480 dp 未満または文字倍率1.5倍以上は `TimeInput` を使う。入力欄の標準ラベルは端末の言語に従う。保存時に反映する。 [Compose 時刻選択](https://developer.android.com/develop/ui/compose/components/time-pickers) |
| 通知権限の回復 | 警告の `Surface` と `TextButton`。拒否や端末設定によるブロック時に表示し、OS のアプリ別通知設定を開く。通知の曜日と時刻はアプリ側の通知設定がオンのときに表示する。 |
| バックアップの進行・結果 | 既存の Android ファイル選択。処理中は `CircularProgressIndicator` 付きダイアログで重複操作を防ぐ。インポート結果とエラーは `AlertDialog` で表示し、復元できる設定がある場合は「設定も戻す」を表示する。書き出しと削除の完了は `Snackbar`。 |
| すべてのデータを削除 | 2段階の `AlertDialog`。最初の `Checkbox` は設定と API キーも初期化するかを選ぶ。最終確定はエラーロールの Filled `Button`、キャンセルは `TextButton`。 |
| 画面の初期読み込み | インジケーターと「設定を読み込んでいます」。ストレージの集計は対象の行だけを更新する。 |

押下状態とフォーカス表示には M3 と Compose Foundation の既定のインタラクションを使う。ダイアログを表示中は背後への入力をモーダルが受け止める。Motion はアプリバーの折りたたみと既存の画面遷移を使う。

## アクセシビリティと引き継ぎ

- ページタイトルと各セクション名を見出しとして提供する。AI の状態はカード内のテキスト、スイッチの状態は親行のトグルセマンティクスから取得できる。装飾アイコンの読み上げは省略する。
- 行、選択肢、アイコンボタンのタッチ領域は48 dp 四方以上を目標とする。画面を開いた直後、モーダルを閉じた直後、ペインが増減した直後を TalkBack とハードウェアキーボードで確認する。 [Compose のアクセシビリティ既定動作](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- 320 dp・文字倍率2倍、幅599/600 dp と839/840 dp、高さの低い横画面、縦ヒンジ、ダイナミック配色を確認する。時刻入力では IME 表示中の保存・キャンセル、通知では拒否直後の警告読み上げ、削除では2段階ともキャンセルできることを確認する。
- 設定の UI は `SettingsScreen.kt`、セクション順と文言は `SettingsSections.kt`、部品は `SettingsComponents.kt` と `SettingsDialogs.kt`。時刻選択の実験 API は `TimeDialog` の中に限定している。
- 画面の描画は `SettingsScreenshotTest`、テーマ・時刻・削除などのモーダルは `SettingsDialogScreenshotTest` で確認する。記録方法は [スクリーンショットテスト](screenshot-testing.md) を参照。

ライト・ダークの描画例は `app/build/outputs/roborazzi` に出力される。3つの幅は `settings_compact_*`、`settings_medium_*`、`settings_expanded_*`、320 dp・文字倍率2倍は `settings_compact_large_text_*`、時刻入力は `settings_dialog_time_input_*`。

### セルフ監査

| 観点 | 評価（0–2） | 根拠 |
| --- | --- | --- |
| タスクと情報階層 | 2 | AI の状態、日常設定、管理、削除を見出しから追える。 |
| コンポーネントの意味 | 2 | スイッチ、単一選択、確定操作と各状態を対応付けた。 |
| カラーロール | 2 | ブランドの主要な文字色を算出し、画面はロールを参照する。 |
| 適応型レイアウト | 1 | 3幅を実装。実機での分割画面とヒンジは確認対象。 |
| 状態とフィードバック | 2 | 権限の回復、処理中、結果、エラーを該当箇所で扱う。 |
| アクセシビリティ | 1 | 見出し・役割・状態を実装。TalkBack、キーボード操作、実端末の状態レイヤーの確認が残る。 |
| Expressive の強弱 | 2 | AI への遷移に色と形状の変化を集中させた。 |
