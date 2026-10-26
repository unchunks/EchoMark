# サードパーティのライセンス

アプリ(release)に同梱されるライブラリ・モデルの出典とライセンスの記録。設定画面の「オープンソースライセンス」(`ui/settings/SettingsDialogs.kt` の `OPEN_SOURCE_LIBRARIES` / `NON_OPEN_SOURCE_COMPONENTS`)は、この記録をプロジェクトごとにまとめたもの。依存や同梱するモデル・素材を変えたら、両方を直す。

- 確認日: 2026-10-19
- 対象: `refactor/app-cleanup`(13ed26b)の依存。`releaseRuntimeClasspath` の 287 個と、assets のモデル

## 確かめ方

1. 同梱される依存(推移的なものを含む)を出す。

   ```sh
   ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
   ```

2. 各 artifact の POM の `<licenses>` を読む(無いときは親 POM をたどる)。POM は Google Maven(`dl.google.com/dl/android/maven2`)か Maven Central(`repo1.maven.org/maven2`)のもの。どちらにあったかは付録の表に書いた。
3. POM だけでは分からないもの(オープンソースでない規約、ネイティブライブラリ、AAR に入っているファイル)は、配布元のページ・リポジトリの LICENSE・AAR の中身で確かめた。下の表の「出典」に書く。

## 画面の項目とライセンス

番号は付録の表の「画面の項目」と対応する。「表記・同梱」は、アプリを配布するときにライセンスが求めること。

### オープンソース

| # | 画面の項目 | ライセンス | 商用利用 | 表記・同梱 | 出典 |
|---|---|---|---|---|---|
| O1 | Android Jetpack(Compose・Room・WorkManager・DataStore・Navigation・Glance・ExifInterface ほか) | Apache License 2.0 | 可 | ライセンス文の写しを渡す(第4条 a)。NOTICE があればその内容も(第4条 d) | 各 POM。Coil が使う JetBrains の Compose Multiplatform(`org.jetbrains.compose.*`・`org.jetbrains.androidx.*`)も POM は Apache 2.0。Android 向けは中身の無い入れ物(`org.jetbrains.compose.*` の AAR は classes.jar が 300 バイト未満、`org.jetbrains.androidx.*` は jar も無い)のため、この項目に含めた |
| O2 | Kotlin・kotlinx.coroutines・kotlinx.serialization | Apache License 2.0 | 可 | O1 と同じ | 各 POM |
| O3 | Dagger / Hilt | Apache License 2.0 | 可 | O1 と同じ | 各 POM |
| O4 | Material Icons | Apache License 2.0 | 可 | O1 と同じ | `androidx.compose.material:material-icons-*` の POM。以前の一覧にあった Material Components(`com.google.android.material`)は依存に無いので外した(テーマは端末標準の `android:Theme.Material`) |
| O5 | MediaPipe Tasks(テキスト埋め込み・LLM 推論) | Apache License 2.0 | 可 | O1 と同じ | `tasks-core`・`tasks-text`・`tasks-genai` の POM。AAR の `META-INF/NOTICE` に、中で使っている部品のライセンス文と、性能・利用状況の指標(metrics)を Google に送る旨の通知がある(下の「ライブラリに含まれる部品」) |
| O6 | ObjectBox Java | Apache License 2.0 | 可 | O1 と同じ | `objectbox-java`・`objectbox-java-api`・`objectbox-android` の POM。<https://objectbox.io/faq/> の表(Java libraries: Apache 2.0) |
| O7 | OkHttp・Okio | Apache License 2.0 | 可 | O1 と同じ | 各 POM |
| O8 | Coil | Apache License 2.0 | 可 | O1 と同じ | 各 POM |
| O9 | Timber | Apache License 2.0 | 可 | O1 と同じ | POM |
| O10 | PdfBox-Android | Apache License 2.0 | 可 | O1 と同じ。同梱部品の条件も(下記) | POM と <https://github.com/TomRoush/PdfBox-Android/blob/master/LICENSE.txt> |
| O11 | Guava・Flogger | Apache License 2.0 | 可 | O1 と同じ | 各 POM(MediaPipe・ML Kit が使う) |
| O12 | Jackson・ClassMate・JSON Schema Generator・Swagger Annotations | Apache License 2.0 | 可 | O1 と同じ。jackson-core の同梱部品の条件も(下記) | 各 POM(Anthropic Java SDK が使う) |
| O13 | Firebase Components・Firebase Encoders・Google Data Transport | Apache License 2.0 | 可 | O1 と同じ | 各 POM(ML Kit が使う) |
| O14 | Accompanist・greenrobot Essentials | Apache License 2.0 | 可 | O1 と同じ | 各 POM(Coil・ObjectBox が使う) |
| O15 | javax.inject・Jakarta Inject・JSR 305・JSpecify・Error Prone・J2ObjC・JetBrains のアノテーション | Apache License 2.0 | 可 | O1 と同じ | 各 POM |
| O16 | jsoup | MIT License | 可 | 著作権表示と許諾文を含める | POM |
| O17 | Anthropic Java SDK | MIT License | 可 | O16 と同じ | POM(名前だけで URL は無い)と <https://github.com/anthropics/anthropic-sdk-java/blob/main/LICENSE>(Copyright 2023 Anthropic, PBC) |
| O18 | Standard Webhooks・SLF4J | MIT License | 可 | O16 と同じ | 各 POM(Anthropic Java SDK が使う) |
| O19 | Animal Sniffer のアノテーション | MIT License | 可 | O16 と同じ | 親 POM(`animal-sniffer-parent`) |
| O20 | Checker Framework のアノテーション | MIT License / GPL 2.0 with Classpath Exception | 可 | O16 と同じ | `checker-compat-qual` の POM が2つを挙げているので、画面にも両方を載せた。<https://github.com/typetools/checker-framework/blob/master/LICENSE.txt> は注釈の jar(`checker-qual*.jar`)を MIT とするが、`checker-compat-qual` の名前は無い |
| O21 | Protocol Buffers | BSD 3-Clause License | 可 | バイナリで配布するときは、著作権表示・条件・免責を文書などに含める | `com.google.protobuf:protobuf-javalite` の親 POM と、DataStore・Glance の中に入っている protobuf(`datastore-preferences-external-protobuf`・`glance-appwidget-external-protobuf`)の POM |
| O22 | Bouncy Castle | Bouncy Castle Licence | 可 | 著作権表示と許諾文を含める | 各 POM と <https://www.bouncycastle.org/licence.html>(配布元が「MIT ライセンスと同じに読む」と説明している)。PdfBox-Android が使う |
| O23 | LMDB(ObjectBox のネイティブライブラリに含まれる) | OpenLDAP Public License 2.8 | 可 | バイナリで配布するときは著作権表示と通知を再現し、このライセンスの写しを含める。作者の名前を宣伝に使わない | `objectbox-android-db` の POM と、ObjectBox Binary Licence の Schedule A |

### オープンソースでないもの(利用規約)

| # | 画面の項目 | 規約 | 商用利用 | 表記・同梱・ほかの義務 | 出典 |
|---|---|---|---|---|---|
| N1 | ML Kit(文字認識・画像ラベル) | ML Kit Terms of Service(Google APIs 利用規約にも従う) | 規約のページに明記なし(禁止の記載もなし) | クレジット表記の要求はない。ML Kit が性能・利用状況の指標(metrics)を Google に送ることを、法律が求める範囲でアプリのユーザーに知らせる。リバースエンジニアリングをしない | `com.google.mlkit:*`・`play-services-mlkit-*` の POM と <https://developers.google.com/ml-kit/terms> |
| N2 | Google Play 開発者サービス・ODML Image | Android Software Development Kit License | SDK を使ってアプリを開発するためのライセンス(第3.1条)。商用利用の明記なし | 表記の要求はない。SDK に付いている権利表示を消さない(第3.8条) | `play-services-base`・`play-services-basement`・`play-services-tasks`・`com.google.android.odml:image` の POM と <https://developer.android.com/studio/terms> |
| N3 | ObjectBox(ネイティブライブラリ) | ObjectBox Binary Licence | アプリを作るための利用を許可(第2条 a)。ObjectBox の競合にあたる者は除く。取り消しうるライセンスで、FAQ は「いまのライセンスは暫定」と書いている | 権利表示を消さない(第2条 b(iv)・第4条 b)。Schedule A の第三者ライセンス(O23 LMDB など)に従う(第3条) | `objectbox-android-db` の POM、<https://objectbox.io/0209-ob-binary-license/>、<https://objectbox.io/faq/> |
| N4 | EmbeddingGemma(埋め込みモデル) | Gemma Terms of Use | 規約のページに明記なし(禁止の記載もなし) | 配布するとき(第3.1条): 受け取る人に規約の写しを渡す。`Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms` と書いた「Notice」ファイルを付ける。第3.2条の利用制限を利用条件に入れて、ユーザーに知らせる | <https://ai.google.dev/gemma/terms>。モデルは `app/src/main/assets/embeddinggemma-300m/embedding_gemma.task`(git の対象外)としてアプリに同梱する。ファイルの入手元はこの記録では確かめていない |

## 通信先のサービス(資料を探す)

「資料を探す」画面(`data/discover/`)は、次のサービスを API キー無しで検索する。アプリに同梱するものではないが、利用の条件をここに記録する。確認日: 2026-10-26。

| 検索先 | 使う窓口 | 条件 | 確かめたこと / 確かめていないこと |
|---|---|---|---|
| はてなブックマーク(記事) | 全文検索の RSS(`b.hatena.ne.jp/q/{語}?mode=rss&target=text`) | はてなの利用規約に従う | RSS が鍵なしで返ることを curl で確かめた。RSS をアプリから使うことについての規約の条文は**確かめていない**(要確認) |
| Wikipedia(百科事典) | MediaWiki の検索 API(`ja.wikipedia.org/w/api.php`) | 本文は CC BY-SA 4.0。API は User-Agent でアプリ名と連絡先を名乗る(<https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy>) | 方針の文面を確かめ、User-Agent を `EchoMark/1.0 (Android; +https://github.com/unchunks/EchoMark)` にした。画面には検索結果の抜粋(数十字)と「Wikipedia」を出し、記事へリンクする。抜粋の表示に CC BY-SA の表記がどこまで要るかは**確かめていない** |
| arXiv(論文) | arXiv API(`export.arxiv.org/api/query`) | 3 秒に 1 回まで・同時に 1 接続まで。メタデータ(タイトル・概要・著者)は CC0。arXiv が後援しているように見せない(<https://info.arxiv.org/help/api/tou.html>) | 規約の文面を確かめた。検索はユーザーの操作ごとに 1 回なので、上限は超えない |
| YouTube(動画) | InnerTube の検索(`youtubei/v1/search`。字幕の取得と同じ非公開の窓口) | 公開 API ではない。YouTube の利用規約は自動化された手段での利用を制限している | 字幕の取得(`YouTubeTranscriptFetcher`)と同じ扱いにした。規約との関係は**確かめていない**(要確認)。仕様が変わったときは検索先の失敗として扱い、ほかの検索先の結果は出す |

## ライブラリに含まれる部品(画面には載せていない)

画面は artifact(POM)の単位でまとめている。artifact の中に入っている、別のライセンスの部品は次のとおり。

- **MediaPipe Tasks**(`tasks-core`・`tasks-text` の `META-INF/NOTICE`): Abseil・TensorFlow・XNNPACK・OpenCV・ICU4C・LLVM・BoringSSL・zlib など約 180 個のライセンス文。先頭に「MediaPipe Tasks Privacy Notice」があり、入力データは端末内で処理して Google に送らないこと、API の性能・利用状況の指標は Google に送ること、その処理についてユーザーの同意を得るのはアプリの開発者の責任(法律が求める範囲で)であることが書かれている。`tasks-genai` の AAR にはライセンスのファイルが無い。
- **ML Kit・Google Play 開発者サービス・ODML Image**(各 AAR の `third_party_licenses.txt` / `.json`): 中で使っているオープンソースの一覧。文字認識の `text-recognition-bundled-common` と `text-recognition-japanese` は約 200 個(Abseil・TensorFlow・OpenCV・Leptonica・ICU4C・Protocol Buffers・zlib など)。
- **jackson-core**(`META-INF/FastDoubleParser-NOTICE`・`thirdparty-LICENSE`): FastDoubleParser(MIT)と、それが取り込んだ fast_double_parser(Boost Software License)・fast_float(MIT)・bigint(BSD 2-Clause)。
- **PdfBox-Android**: リポジトリの LICENSE.txt に、PDFBox / FontBox への寄稿(BSD 形式)、Adobe Font Metrics(AFM。Adobe の許諾文)、Adobe の CMap(BSD 形式)、PaDaF PDF/A preflight(Apache 2.0)の条件がある。AAR の assets には、これらのほかに次のものが入っている。
  - Liberation Sans 2.1.5(`assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf`): フォントの name テーブルに「Licensed under the SIL Open Font License, Version 1.1」、著作権は Google Corporation(2010)と Red Hat, Inc.(2012)。LICENSE.txt には書かれていない。SIL OFL 1.1 は、ソフトウェアに同梱して配布するときに著作権表示とライセンス文を含めることを求める。
  - グリフ一覧(`glyphlist/*.txt`)と Unicode のデータ(`Scripts.txt`・`BidiMirroring.txt`): ライセンスは確かめていない。
- **ObjectBox のネイティブライブラリ**: Binary Licence の Schedule A に、LMDB(OpenLDAP Public License)のほか、FlatBuffers・FreeMarker・greenDAO・Javassist・Moshi・Kotlin(Apache 2.0)がある。

## ビルド時だけ使うもの(アプリには入らない)

- ObjectBox の Gradle プラグイン: GNU GPL v3(<https://objectbox.io/faq/>)。注釈処理はビルドのログに「Licensed under GNU Affero General Public License, Version 3」と出す。生成されるコード(`MyObjectBox.java` など)にはライセンスの記載が無い。
- Hilt・Room のコンパイラ、KSP、テストだけで使うもの(JUnit・Robolectric・Roborazzi・MockWebServer など)は `releaseRuntimeClasspath` に入らないので、この記録の対象外。

## まだ対応していないこと

- 画面にはライセンスの名前だけを出していて、ライセンス文の全文・著作権表示・NOTICE は出していない。Apache 2.0・MIT・BSD・OpenLDAP・SIL OFL はどれも、配布物に全文や著作権表示を含めることを求める。MediaPipe の NOTICE や ML Kit などの `third_party_licenses` の部品も、画面では見られない。
- EmbeddingGemma を同梱しているので、Gemma Terms of Use 第3.1条の「Notice」ファイルと規約の写し、利用制限の告知が要る。
- ML Kit の規約と MediaPipe の通知は、指標(metrics)を Google に送ることをユーザーに知らせるよう求めている。設定画面の「プライバシー」の説明には、まだ書いていない。

## 付録: 同梱される artifact の一覧

`releaseRuntimeClasspath` で解決された版。POM のライセンスは表記のまま(複数あるときは ` / ` で区切った)。

| 画面の項目 | artifact | 版 | POM のライセンス | POM の取得元 |
|---|---|---|---|---|
| O1 Android Jetpack | `androidx.activity:activity` | 1.13.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.activity:activity-compose` | 1.13.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.activity:activity-ktx` | 1.13.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.annotation:annotation` | 1.10.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.annotation:annotation-experimental` | 1.5.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.annotation:annotation-jvm` | 1.10.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.appcompat:appcompat` | 1.7.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.appcompat:appcompat-resources` | 1.7.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.arch.core:core-common` | 2.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.arch.core:core-runtime` | 2.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.autofill:autofill` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.collection:collection` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.collection:collection-jvm` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.collection:collection-ktx` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose:compose-bom` | 2026.08.00 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.animation:animation` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.animation:animation-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.animation:animation-core` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.animation:animation-core-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.foundation:foundation` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.foundation:foundation-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.foundation:foundation-layout` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.foundation:foundation-layout-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material:material-ripple` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material:material-ripple-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3:material3` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3:material3-android` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3.adaptive:adaptive` | 1.3.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3.adaptive:adaptive-android` | 1.3.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3.adaptive:adaptive-layout` | 1.3.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.material3.adaptive:adaptive-layout-android` | 1.3.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-annotation` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-annotation-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-retain` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-retain-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-saveable` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.runtime:runtime-saveable-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-geometry` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-geometry-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-graphics` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-graphics-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-text` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-text-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-tooling-preview` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-tooling-preview-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-unit` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-unit-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-util` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.compose.ui:ui-util-android` | 1.12.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.concurrent:concurrent-futures` | 1.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.concurrent:concurrent-futures-ktx` | 1.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.core:core` | 1.19.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.core:core-ktx` | 1.19.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.core:core-remoteviews` | 1.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.core:core-viewtree` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.cursoradapter:cursoradapter` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.customview:customview` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.customview:customview-poolingcontainer` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-android` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-core` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-core-android` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-core-okio` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-core-okio-jvm` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-preferences` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-preferences-android` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-preferences-core` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-preferences-core-android` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.datastore:datastore-preferences-proto` | 1.2.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.drawerlayout:drawerlayout` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.emoji2:emoji2` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.emoji2:emoji2-views-helper` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.exifinterface:exifinterface` | 1.4.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.fragment:fragment` | 1.5.4 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.glance:glance` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.glance:glance-appwidget` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.glance:glance-appwidget-proto` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.glance:glance-material3` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.graphics:graphics-path` | 1.0.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.hilt:hilt-common` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.hilt:hilt-lifecycle-viewmodel` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.hilt:hilt-lifecycle-viewmodel-compose` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.hilt:hilt-navigation-compose` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.hilt:hilt-work` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.interpolator:interpolator` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-common` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-common-java8` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-common-jvm` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-livedata` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-livedata-core` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-process` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-service` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-compose-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.11.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.loader:loader` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-common` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-common-android` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-compose` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-compose-android` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-runtime` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigation:navigation-runtime-android` | 2.9.6 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigationevent:navigationevent` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigationevent:navigationevent-android` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigationevent:navigationevent-compose` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.navigationevent:navigationevent-compose-android` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.profileinstaller:profileinstaller` | 1.4.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.resourceinspection:resourceinspection-annotation` | 1.0.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.room:room-common` | 2.8.5 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.room:room-common-jvm` | 2.8.5 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.room:room-ktx` | 2.8.5 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.room:room-runtime` | 2.8.5 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.room:room-runtime-android` | 2.8.5 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.savedstate:savedstate` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.savedstate:savedstate-android` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.savedstate:savedstate-compose` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.savedstate:savedstate-compose-android` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.savedstate:savedstate-ktx` | 1.4.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.sqlite:sqlite` | 2.6.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.sqlite:sqlite-android` | 2.6.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.sqlite:sqlite-framework` | 2.6.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.sqlite:sqlite-framework-android` | 2.6.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.startup:startup-runtime` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.tracing:tracing` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.tracing:tracing-ktx` | 1.2.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.vectordrawable:vectordrawable` | 1.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.vectordrawable:vectordrawable-animated` | 1.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.viewpager:viewpager` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.window:window` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.window:window-core` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.window:window-core-android` | 1.5.0 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.work:work-runtime` | 2.11.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `androidx.work:work-runtime-ktx` | 2.11.2 | The Apache Software License, Version 2.0 | Google Maven |
| O1 Android Jetpack | `org.jetbrains.androidx.lifecycle:lifecycle-common` | 2.9.6 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.androidx.lifecycle:lifecycle-runtime` | 2.9.6 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose` | 2.9.6 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.androidx.savedstate:savedstate` | 1.3.6 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.androidx.savedstate:savedstate-compose` | 1.3.6 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.animation:animation` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.animation:animation-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.animation:animation-core` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.animation:animation-core-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.foundation:foundation` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.foundation:foundation-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.foundation:foundation-layout` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.foundation:foundation-layout-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.runtime:runtime` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.runtime:runtime-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.runtime:runtime-saveable` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.runtime:runtime-saveable-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-geometry` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-geometry-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-graphics` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-graphics-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-text` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-text-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-unit` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-unit-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-util` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O1 Android Jetpack | `org.jetbrains.compose.ui:ui-util-android` | 1.12.0 | The Apache Software License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlin:kotlin-reflect` | 1.9.25 | The Apache License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.10 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlin:kotlin-stdlib-common` | 2.4.10 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlin:kotlin-stdlib-jdk7` | 1.9.0 | The Apache License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlin:kotlin-stdlib-jdk8` | 1.9.0 | The Apache License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.10.2 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.10.2 | Apache-2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.7.3 | The Apache Software License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.7.3 | The Apache Software License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.7.3 | The Apache Software License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | The Apache Software License, Version 2.0 | Maven Central |
| O2 Kotlin・kotlinx | `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.7.3 | The Apache Software License, Version 2.0 | Maven Central |
| O3 Dagger / Hilt | `com.google.dagger:dagger` | 2.60.1 | Apache 2.0 | Maven Central |
| O3 Dagger / Hilt | `com.google.dagger:dagger-lint-aar` | 2.60.1 | Apache 2.0 | Maven Central |
| O3 Dagger / Hilt | `com.google.dagger:hilt-android` | 2.60.1 | Apache 2.0 | Maven Central |
| O3 Dagger / Hilt | `com.google.dagger:hilt-core` | 2.60.1 | Apache 2.0 | Maven Central |
| O4 Material Icons | `androidx.compose.material:material-icons-core` | 1.7.8 | The Apache Software License, Version 2.0 | Google Maven |
| O4 Material Icons | `androidx.compose.material:material-icons-core-android` | 1.7.8 | The Apache Software License, Version 2.0 | Google Maven |
| O4 Material Icons | `androidx.compose.material:material-icons-extended` | 1.7.8 | The Apache Software License, Version 2.0 | Google Maven |
| O4 Material Icons | `androidx.compose.material:material-icons-extended-android` | 1.7.8 | The Apache Software License, Version 2.0 | Google Maven |
| O5 MediaPipe Tasks | `com.google.mediapipe:tasks-core` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O5 MediaPipe Tasks | `com.google.mediapipe:tasks-genai` | 0.10.35 | The Apache Software License, Version 2.0 | Google Maven |
| O5 MediaPipe Tasks | `com.google.mediapipe:tasks-text` | 1.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O6 ObjectBox Java | `io.objectbox:objectbox-android` | 5.4.2 | The Apache Software License, Version 2.0 | Maven Central |
| O6 ObjectBox Java | `io.objectbox:objectbox-java` | 5.4.2 | The Apache Software License, Version 2.0 | Maven Central |
| O6 ObjectBox Java | `io.objectbox:objectbox-java-api` | 5.4.2 | The Apache Software License, Version 2.0 | Maven Central |
| O7 OkHttp・Okio | `com.squareup.okhttp3:okhttp` | 5.5.0 | The Apache Software License, Version 2.0 | Maven Central |
| O7 OkHttp・Okio | `com.squareup.okhttp3:okhttp-android` | 5.5.0 | The Apache Software License, Version 2.0 | Maven Central |
| O7 OkHttp・Okio | `com.squareup.okio:okio` | 3.18.1 | The Apache Software License, Version 2.0 | Maven Central |
| O7 OkHttp・Okio | `com.squareup.okio:okio-jvm` | 3.18.1 | The Apache Software License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-compose` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-compose-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-compose-core` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-compose-core-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-core` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-core-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-network-core` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-network-core-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-network-okhttp` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O8 Coil | `io.coil-kt.coil3:coil-network-okhttp-android` | 3.6.3 | The Apache License, Version 2.0 | Maven Central |
| O9 Timber | `com.jakewharton.timber:timber` | 5.0.1 | The Apache Software License, Version 2.0 | Maven Central |
| O10 PdfBox-Android | `com.tom-roush:pdfbox-android` | 2.0.27.0 | The Apache Software License, Version 2.0 | Maven Central |
| O11 Guava・Flogger | `com.google.flogger:flogger` | 0.6 | Apache 2.0 | Maven Central |
| O11 Guava・Flogger | `com.google.flogger:flogger-system-backend` | 0.6 | Apache 2.0 | Maven Central |
| O11 Guava・Flogger | `com.google.guava:failureaccess` | 1.0.1 | The Apache Software License, Version 2.0 | Maven Central |
| O11 Guava・Flogger | `com.google.guava:guava` | 27.0.1-android | The Apache Software License, Version 2.0 | Maven Central |
| O11 Guava・Flogger | `com.google.guava:listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml:classmate` | 1.7.0 | Apache License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson:jackson-bom` | 2.19.4 | Apache License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.core:jackson-annotations` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.core:jackson-core` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.core:jackson-databind` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.19.4 | The Apache Software License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.github.victools:jsonschema-generator` | 4.38.0 | The Apache License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.github.victools:jsonschema-module-jackson` | 4.38.0 | The Apache License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `com.github.victools:jsonschema-module-swagger-2` | 4.38.0 | The Apache License, Version 2.0 | Maven Central |
| O12 Jackson ほか | `io.swagger.core.v3:swagger-annotations` | 2.2.50 | Apache License 2.0 | Maven Central |
| O13 Firebase・Data Transport | `com.google.android.datatransport:transport-api` | 3.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.android.datatransport:transport-backend-cct` | 3.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.android.datatransport:transport-runtime` | 3.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.firebase:firebase-annotations` | 16.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.firebase:firebase-components` | 16.1.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.firebase:firebase-encoders` | 17.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.firebase:firebase-encoders-json` | 18.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O13 Firebase・Data Transport | `com.google.firebase:firebase-encoders-proto` | 16.0.0 | The Apache Software License, Version 2.0 | Google Maven |
| O14 Accompanist・greenrobot Essentials | `com.google.accompanist:accompanist-drawablepainter` | 0.37.3 | The Apache Software License, Version 2.0 | Maven Central |
| O14 Accompanist・greenrobot Essentials | `org.greenrobot:essentials` | 3.1.0 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `com.google.code.findbugs:jsr305` | 3.0.2 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `com.google.errorprone:error_prone_annotations` | 2.50.0 | Apache 2.0 | Maven Central |
| O15 アノテーション | `com.google.j2objc:j2objc-annotations` | 1.1 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `jakarta.inject:jakarta.inject-api` | 2.0.1 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `javax.inject:javax.inject` | 1 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `org.jetbrains:annotations` | 23.0.0 | The Apache Software License, Version 2.0 | Maven Central |
| O15 アノテーション | `org.jspecify:jspecify` | 1.0.0 | The Apache License, Version 2.0 | Maven Central |
| O16 jsoup | `org.jsoup:jsoup` | 1.23.2 | The MIT License | Maven Central |
| O17 Anthropic Java SDK | `com.anthropic:anthropic-java` | 2.66.0 | MIT | Maven Central |
| O17 Anthropic Java SDK | `com.anthropic:anthropic-java-client-okhttp` | 2.66.0 | MIT | Maven Central |
| O17 Anthropic Java SDK | `com.anthropic:anthropic-java-core` | 2.66.0 | MIT | Maven Central |
| O18 Standard Webhooks・SLF4J | `com.standardwebhooks:standardwebhooks` | 1.2.0 | The MIT License | Maven Central |
| O18 Standard Webhooks・SLF4J | `org.slf4j:slf4j-api` | 2.0.16 | MIT License | Maven Central |
| O19 Animal Sniffer | `org.codehaus.mojo:animal-sniffer-annotations` | 1.17 | MIT license | Maven Central |
| O20 Checker Framework | `org.checkerframework:checker-compat-qual` | 2.5.3 | GNU General Public License, version 2 (GPL2), with the classpath exception / The MIT License | Maven Central |
| O21 Protocol Buffers | `androidx.datastore:datastore-preferences-external-protobuf` | 1.2.1 | BSD-3-Clause | Google Maven |
| O21 Protocol Buffers | `androidx.glance:glance-appwidget-external-protobuf` | 1.2.0 | BSD-3-Clause | Google Maven |
| O21 Protocol Buffers | `com.google.protobuf:protobuf-javalite` | 4.26.1 | BSD-3-Clause | Maven Central |
| O22 Bouncy Castle | `org.bouncycastle:bcpkix-jdk15to18` | 1.72 | Bouncy Castle Licence | Maven Central |
| O22 Bouncy Castle | `org.bouncycastle:bcprov-jdk15to18` | 1.72 | Bouncy Castle Licence | Maven Central |
| O22 Bouncy Castle | `org.bouncycastle:bcutil-jdk15to18` | 1.72 | Bouncy Castle Licence | Maven Central |
| N1 ML Kit | `com.google.android.gms:play-services-mlkit-image-labeling` | 16.0.8 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.android.gms:play-services-mlkit-text-recognition-common` | 19.1.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.android.gms:play-services-mlkit-text-recognition-japanese` | 16.0.1 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:common` | 18.11.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:image-labeling-common` | 18.1.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:image-labeling-default-common` | 17.0.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:text-recognition-bundled-common` | 17.0.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:text-recognition-japanese` | 16.0.1 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:vision-common` | 17.3.0 | ML Kit Terms of Service | Google Maven |
| N1 ML Kit | `com.google.mlkit:vision-interfaces` | 16.3.0 | ML Kit Terms of Service | Google Maven |
| N2 Google Play 開発者サービス・ODML Image | `com.google.android.gms:play-services-base` | 18.5.0 | Android Software Development Kit License | Google Maven |
| N2 Google Play 開発者サービス・ODML Image | `com.google.android.gms:play-services-basement` | 18.4.0 | Android Software Development Kit License | Google Maven |
| N2 Google Play 開発者サービス・ODML Image | `com.google.android.gms:play-services-tasks` | 18.2.0 | Android Software Development Kit License | Google Maven |
| N2 Google Play 開発者サービス・ODML Image | `com.google.android.odml:image` | 1.0.0-beta1 | Android Software Development Kit License | Google Maven |
| N3 ObjectBox(ネイティブ)・O23 LMDB | `io.objectbox:objectbox-android-db` | 5.4.2 | ObjectBox Binary Licence / The Apache Software License, Version 2.0 / The OpenLDAP Public License, Version 2.8, 17 August 2003 | Maven Central |
