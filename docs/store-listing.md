# Google Play の掲載情報(画像・動画・説明文)

ストアの掲載に使う画像・紹介動画・説明文と、その作り直し方。画像と動画は本物の画面(`XxxContent`)に
サンプルデータを渡して描くので、画面を変えたら作り直せば、ストアの見た目も追いつく。

## 置き場所

[fastlane supply](https://docs.fastlane.tools/actions/supply/) の形にしてある(fastlane を使わなくても、この中身を Play Console に貼ればよい)。

| もの | ファイル | Play Console の決まり |
| --- | --- | --- |
| アプリ名 | `fastlane/metadata/android/ja-JP/title.txt` | 30 文字まで |
| 簡単な説明 | `.../short_description.txt` | 80 文字まで |
| 詳しい説明 | `.../full_description.txt` | 4,000 文字まで |
| リリースノート(versionCode 1) | `.../changelogs/1.txt` | 500 文字まで |
| アイコン | `.../images/icon.png` | 512×512、32 ビット PNG、1 MB まで。角丸は Play が付けるので正方形のまま |
| フィーチャー グラフィック | `.../images/featureGraphic.png` | 1024×500、アルファなしの PNG か JPEG |
| スマートフォンのスクリーンショット(8 枚) | `.../images/phoneScreenshots/` | 9:16。おすすめ枠に載るには 1080×1920 以上を 4 枚以上 |
| 7 インチ・10 インチ タブレット(各 5 枚) | `.../images/sevenInchScreenshots/`・`tenInchScreenshots/` | 16:9 の 1920×1080(4 枚以上)。両方同じ画像 |
| 紹介動画(MP4) | `build/store-assets/echomark_promo.mp4`(コミットしない) | YouTube に上げて URL を入れる |
| 画像・動画の設定 | `tools/store-assets/store-assets.json` | 下の「設定」 |

文字数は `node` で数えられる(Play は1文字ずつ数える)。

## 作り直し方(コマンド一つ)

アプリを更新したら、リポジトリ直下で次を実行する。画面の変更はそのまま画像に入る。

```powershell
.\store-assets.cmd           # 画像だけ(数分)
.\store-assets.cmd -Video    # 画像と紹介動画(動画のコマを 1,000 枚ほど描くので 20〜30 分)
.\store-assets.cmd -Video -SkipRender   # 描き直さず、すでに描いたものから仕上げだけ
```

- `JAVA_HOME`・`ANDROID_HOME` が無い・壊れているときは、Android Studio の JDK(`Program Files\Android\*\jbr`)と
  `local.properties` の SDK を探して使う。部品(components)の場所は、ふだんのビルドと同じ探し方。
- 部品のコピーが `origin/main` より古ければ、描く前に知らせる。古いままだと、ビルドが
  `… could not be resolved`(KSP など)で失敗する。そのときは知らせに出るコマンド(`git -C <部品> pull --ff-only`)で
  最新にしてからやり直す。作業の途中の変更があるかもしれないので、自動では pull しない
- コードが変わっていなくても、毎回必ず描き直す(Gradle に毎回ちがう値 `-Pechomark.storeAssetsRun` を渡している)。
- 中身: `store-assets.cmd` → `tools/store-assets/make-store-assets.ps1` が次を順に行う。
  1. `app/src/test/java/com/unchunks/echomark/screenshot/store/` のテストで描く
     - `StoreGraphicsScreenshotTest`(アイコン・フィーチャー グラフィック)・`StorePhoneScreenshotTest`・`StoreTabletScreenshotTest`
       (設定の `phone`・`tablet` を1枚ずつ)。大きな画像を描くので、Roborazzi の記録のときだけ動く(CI では飛ばす)
     - `StorePromoVideoTest` … 紹介動画のコマ。環境変数 `ECHOMARK_STORE_VIDEO=1`(`-Video`)のときだけ動く
     - `StoreScreens`(写せる画面)・`StoreSamples`(サンプルデータ)・`StoreFrames`(見出し・端末の枠・背景)・`StoreConfig`(設定の読み込み)
  2. `tools/store-assets/StoreAssetTool.cs` で仕上げる。Windows の .NET Framework の `csc` でビルドする小さな道具で、
     PNG のアルファを落とし(Play がフィーチャー グラフィックにアルファ付きを受け付けないため)、連番の PNG を
     Windows 標準の Media Foundation で H.264 の MP4 にする。ffmpeg などは要らない
  3. `fastlane/metadata/android/ja-JP/images/` へ写す(前の画像は消してから写す)
- サンプルデータのサイト名・URL は実在しないもの(`example.jp`)にしている。他社の名前や商標を写さないため。
- 画素数は「dp × 画面密度」で決まる。スマートフォンは 360×640dp × xxhdpi = 1080×1920、タブレットは
  1280×720dp × hdpi = 1920×1080、フィーチャー グラフィックは 512×250dp × xhdpi = 1024×500。

## 設定(`tools/store-assets/store-assets.json`)

フォント・文字の大きさ・色・背景・端末の枠・アプリのデザイン・見出し・写す画面は、このファイルで変える(コードは変えなくてよい)。
変えたら `.\store-assets.cmd` で作り直す。

| 項目 | 意味 |
| --- | --- |
| `text.headline` / `text.caption` | 見出し / 説明の文字。`font` はフォント(下の「フォントの書き方」)。`null` なら既定の書体。`weight` は太さ(100〜900。可変フォントはこの太さで描く)、`size` は大きさ(sp)、`color` は `#RRGGBB` か `#AARRGGBB` |
| `background.colors` | 背景のグラデーションの色(左上から右下へ。1色なら単色) |
| `background.rings` | 背景にアイコンと同じ波紋を薄く描くか |
| `device.frameColor` / `device.statusBarTime` | スマートフォンの枠の色 / ステータスバーの時刻 |
| `app.design` / `app.dark` | 画面のデザイン(`CLASSIC`・`ECHO`・`PAPER`・`CLEAR`・`SOFT`)/ ダークテーマで描くか |
| `app.font` / `app.boldFont` | 枠の中のアプリの画面(とタブレットの画像)の書体。`null` なら実際のアプリと同じ端末の標準の書体。`boldFont` は太字(600 以上)に使う書体で、可変フォントなら省いてよい(太字は太字の太さで描く)。可変フォントでも `"boldFont": "Noto Sans JP Medium"` のように太さを書けば、太字をその太さで描く。太さが1つのフォントは、太字の書体を `boldFont` に書く(無ければ機械的に太らせる)。書体を直に決めている所(ペーパーの明朝など)は変わらない。**実際のアプリの書体は変わらない**ので、Play のおすすめ(実際の画面を載せる)から離れすぎないように使う |
| `featureGraphic.title` / `featureGraphic.tagline` | フィーチャー グラフィックのアプリ名とキャッチコピー |
| `phone[]` | スマートフォンの画像。`file`(ファイル名。Play ではこの順に並ぶ)・`screen`(画面)・`headline`・`caption`(`\n` で改行)・`scroll`(撮る前に下へスクロールする dp。省略で画面ごとの既定) |
| `tablet[]` | タブレットの画像。`file`・`screen`・`scroll` |
| `video` | 紹介動画の見出しの大きさ(`headlineSize`・`captionSize`)、はじめとおわりのロゴの文字(`title`・`tagline`)、場面ごとの見出し(`scenes` の `share`・`organize`・`detail`・`search`・`chat`・`graph`・`ai`) |

`screen` に使える画面:

- スマートフォン: `home`(一覧)・`share`(共有シート)・`detail`(詳細。AI の要約までスクロール)・`search`(意味の検索)・
  `chat`(チャット)・`review`(今週のふり返り)・`graph`(つながりの図)・`ai`(AI の準備)・`explore`(見つける)
- タブレット: `library`(一覧と詳細)・`search`(検索と詳細)・`chat`(会話の一覧とチャット)・`graph`・`explore`

画面を足すときは `StoreScreens.phoneScreens` / `tabletScreens` に名前と画面を足す。

### フォントの書き方

`font`・`boldFont` には、パソコンに入っているフォントの名前か、フォントのファイルのパスを書く。

- **名前**: Windows の「設定 > 個人用設定 > フォント」に出る名前(Windows・macOS・Linux のフォントの置き場所から探す)。
  大文字・小文字・空白・ハイフンの違いは見ない。日本語の名前(「BIZ UDゴシック」)でもよい
  - ファミリーの名前(`"Noto Sans JP"`)… 太さは `weight`(アプリの画面なら、それぞれの文字の太さ)のまま
  - ファミリーの名前 + 太さ(`"Noto Sans JP Medium"`)… その太さで描く。太さは Thin・ExtraLight・Light・Regular・Medium・
    SemiBold・Bold・ExtraBold・Black。可変フォントはその太さで、太さが1つのフォントはいちばん近い太さの書体で描く
  - 書体の名前(`"BIZ UDGothic Bold"`・`"NotoSansJP-Medium"`)… その書体で描く
  - 1つのファイルに書体がいくつも入ったフォント(TTC)は、最初の書体しか使えない(「BIZ UDPGothic」など2つ目以降を書くと、そう知らせて止まる)
- **ファイル**: 絶対パスか、このファイルからの相対パス(TTF・OTF・TTC)。フォントのファイルをリポジトリに置くなら
  `tools/store-assets/fonts/` に置き、`"font": "fonts/xxx.ttf"` と書く

見つからなければ、似た名前を添えて止まる。ストアに載せる画像なので、ライセンスで画像への利用が認められたフォントを使う。例:

```json
"headline": { "font": "Noto Sans JP", "weight": 700, "size": 24, "color": "#FFFFFF" },
"caption": { "font": "Noto Sans JP", "weight": 400, "size": 12.5, "color": "#DBFFFFFF" }

"app": { "design": "CLASSIC", "dark": false, "font": "BIZ UDGothic", "boldFont": "BIZ UDGothic Bold" }
```

## 見出しの方針

- 見出しは画面の内容をそのまま言う短い文にし、画像の 20% 以内に収める(Play のおすすめ)。
  「今すぐ入手」のような行動を促す言葉、「No.1」のような順位の主張は入れない。
- **クラウドの AI は今は使えない(`CLOUD_AI_ENABLED = false`)ので、端末内の AI だけを打ち出している。**
  クラウドの AI を有効にしたら、8 枚目(設定の `8_ai`)・動画の最後の場面(`video.scenes.ai`)・説明文の「端末の中で動く AI」を見直すこと。
- フィーチャー グラフィックは、動画を載せると中央に再生ボタンが重なるので、中央を空けている。

| # | 見出し | 画面 |
| --- | --- | --- |
| 1 | 保存するだけで / AI が要約とタグ付け | 一覧(AI が処理中の1件と、要約・タグの付いたカード) |
| 2 | ブラウザの共有から / そのまま保存 | 共有シート |
| 3 | 長い記事も / まずは要点から | 詳細(AI の要約) |
| 4 | 言葉を忘れても / 意味で見つかる | 検索(意味が近い結果) |
| 5 | 保存したものに / AI が答える | チャット(引用元つきの答え) |
| 6 | 忘れた頃に / もう一度届く | 今週のふり返り |
| 7 | 興味のつながりが / ひと目でわかる | つながりの図 |
| 8 | AI は / 端末の中で動く | はじめにの案内(AI の準備) |

紹介動画(1920×1080・30fps・34 秒・音声なし)は、共有から保存 → AI の自動の整理 → 詳細 → 意味の検索(入力から結果まで)
→ チャット(答えが流れて出る)→ つながりの図 → 端末内 AI、の順。Play では最初の 30 秒が消音で自動再生されるので、
説明は見出しの文字で伝え、最初の 3 秒以内に実際の画面を出している。

## Play Console に載せる

### ストアの掲載情報

1. アプリ名・簡単な説明・詳しい説明に、上のファイルの中身を貼る。
2. グラフィックに、アイコン・フィーチャー グラフィック・スマートフォン 8 枚・7 インチ 5 枚・10 インチ 5 枚を上げる。
3. 動画は YouTube に上げてから、動画の URL(`https://www.youtube.com/watch?v=...`。再生リストや時間の指定は付けない)を入れる。
   YouTube では次のようにする(Play の決まり):
   - 公開範囲は「公開」か「限定公開」(「非公開」は不可)
   - 収益化(広告)はオフ。年齢制限なし。埋め込みを許可
   - 「子ども向けではない」を選ぶ
   - 音楽を足すなら、YouTube Studio のオーディオ ライブラリなど、使ってよい音源にする(著作権の申し立てで広告が付くと載せられない)
   - タイトルの案: 「EchoMark ― 保存するだけで AI が整理するブックマーク」
   - 説明の案: 「保存した記事やメモを、端末の中で動く AI が要約・タグ付け。言葉の意味で探せて、保存した内容に質問もできる Android アプリです。」
4. カテゴリは「仕事効率化」、連絡先のメールアドレス(必須)を入れる。

### プライバシーポリシー(必須)

`docs/privacy-policy.md` が下書き。運営者名・連絡先を埋め、誰でも開ける URL(GitHub Pages など)に置いて、
Play Console の「アプリのコンテンツ」→「プライバシー ポリシー」に URL を入れる。

### アプリのコンテンツ(下書き。最後に決めるのは開発者)

- **広告**: 含まない。
- **アプリのアクセス権**: ログインは無い。審査の人が AI を試せるよう、「設定 > AI の設定 > おすすめのモデル からモデルを
  ダウンロードすると、要約・チャットが動きます」と書き添えるとよい。
- **ターゲット層**: AI のチャットがあるので、18 歳以上にしておくのが無難(13 歳未満を含めるとファミリー ポリシーの対象になる)。
- **データ セーフティ**: 開発者のサーバーは無く、解析・広告・クラッシュの送信も無い。端末の外に出るのは次のものだけ
  (経路の一覧は `docs/offline-mode.md`)。「収集」の定義は「端末の外へ送ること」で、利用者が自分で始めた、送ると分かる操作で
  第三者に渡すものは「共有」に当たらない。
  | 端末の外に出るもの | いつ | 送り先 |
  | --- | --- | --- |
  | 保存した URL へのリクエスト(本文・画像・字幕・動画の取得、リンク切れの確認、購読の確認) | 保存したとき・利用者が設定した確認 | それぞれのサイト |
  | チャットの Web 検索・「Web で確かめる」の検索語 | 利用者が API キーを設定し、使ったとき | Brave Search |
  | 「資料を探す」のテーマ | 利用者が探したとき | arXiv・はてなブックマーク・Wikipedia・YouTube |
  | 地図の背景のタイルの要求(見ている範囲) | 場所の画面を開いたとき | OpenFreeMap |
  | AI のモデルのダウンロード | 利用者がダウンロードしたとき | Hugging Face |
  | 保存した内容・AI の処理・音声 | **送らない**(要約・埋め込み・チャット・文字起こし・文字認識・読み上げは端末の中) | ― |
  | 現在地 | **送らない**(押したときに1回だけ調べ、ブックマークに保存するだけ) | ― |

  答え方の案: 「データの収集・共有はない」を基本にし、Web 検索の検索語を「収集」として申告するか(アプリのアクティビティ >
  アプリ内検索の履歴、任意、一時的な処理、共有なし)は開発者が決める。通信は HTTPS。「データの削除をリクエストできる」は、
  アプリの「すべてのデータを削除」で消せる(開発者は預かっていない)。
- **フォアグラウンド サービス**: Android 14 以上が対象なので、種類ごとの申告と、**機能を使う様子の動画の URL** が要る(未作成)。
  | 種類 | 使う所 | 動画に映す操作 |
  | --- | --- | --- |
  | dataSync | 長い AI 処理(`BookmarkAiProcessingWorker`)、動画のファイルのダウンロード(`VideoFileDownloadWorker`)、Android 14 以下の文字起こし・ポッドキャスト作成 | 記事を保存 → 通知に AI の処理の進み具合が出る / 動画のオフライン保存 |
  | mediaProcessing | 長い音声・動画の文字起こし(`ContentExtractionWorker`)、ポッドキャストの作成(`PodcastGenerationWorker`)(Android 15 以上) | 音声ファイルを保存 → 文字起こしの通知 / 「ポッドキャストにする」 |
  | mediaPlayback | ポッドキャストの再生(`PodcastPlaybackService`) | エピソードを再生 → ホームに戻っても通知から操作できる |

  端末内の AI 処理を dataSync にしている点は、申告の選択肢(データの転送・ローカルの処理)と合うかを確かめること。
- **AI が作るコンテンツ**: Play の「AI 生成コンテンツ」ポリシーでは、AI が文章を作るアプリ(チャットが中心の機能など)に、
  **不快な出力をアプリを出ずに報告できる機能**を求めている。要約だけのアプリは対象外だが、EchoMark はチャットがあるので
  対象になる見込みが高い。**今のアプリには報告の機能が無い**ので、公開の前に足すことを勧める。
- **コンテンツのレーティング**: カテゴリは「ユーティリティ、生産性、コミュニケーション、その他」。利用者どうしのやり取り・
  位置情報の共有・アプリ内の購入は無い。保存したページは Web の内容をそのまま表示できる点に注意して答える。
- **権限**: 位置情報はおおよその位置だけ・押したときだけで、バックグラウンドの位置情報は使わない(申告は不要)。
  マイク(音声入力)も押したときだけ。
