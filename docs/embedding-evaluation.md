# 埋め込みモデルの検索品質の評価

埋め込みモデルを替える(EmbeddingGemma → EmbeddingGemma 2 など)前後で、検索の品質としきい値を比べるための仕組み。
実際のモデルが無くてもビルド・単体テストは通る。モデルを使う計測だけが実機(エミュレータ)で動く。

## 構成

| 場所 | 役割 |
| --- | --- |
| `app/src/test/resources/eval/ja_bookmarks.json` | 評価データ。日本語のブックマーク 20 件と質問 14 件(各質問の正解の文書 ID 付き) |
| `app/src/main/java/.../domain/search/RetrievalEval.kt` | 指標(Recall@k・MRR・nDCG@k)、コサイン距離、しきい値の提案。アプリの実行では使わない純粋なロジック |
| `app/src/test/java/.../domain/search/RetrievalEvalTest.kt` | 上の計算の単体テスト(固定のベクトル)と、評価データの整合性の確認。モデル不要 |
| `app/src/androidTest/java/.../EmbeddingEvalInstrumentedTest.kt` | 実際の埋め込みモデルで評価データを埋め込み、指標としきい値の提案を出す |

評価データは `app/build.gradle.kts` の設定で androidTest の assets としても読める(同じファイルを共有)。

## 実行方法

### 単体テスト(モデル不要)

```sh
./gradlew :app:testDebugUnitTest --tests "com.unchunks.echomark.domain.search.RetrievalEvalTest"
```

### 実際のモデルでの計測(実機・エミュレータ)

1. 評価したいモデルを有効にする。
   - 同梱のモデル(`app/src/main/assets/embeddinggemma-300m/embedding_gemma.task`)を評価するなら、そのままでよい。
   - 別のモデルを評価するなら、アプリの「AI の設定 → 検索用の埋め込みモデル」で取り込む(種類は実際のファイルに合わせて選ぶ)。
     計測のテストは取り込み済みのモデルがあればそれを使う。計測が終わったら「同梱のモデルに戻す」で戻せる。
2. 実行する。

   ```sh
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.unchunks.echomark.EmbeddingEvalInstrumentedTest
   ```

3. 結果は Logcat(タグ `EmbeddingEval`)に出る。

   ```sh
   adb logcat -s EmbeddingEval
   ```

モデルが読み込めない環境(assets 未同梱で何も取り込んでいない)では、テストは skip される。

## 出力の見方

```
モデル: EmbeddingGemma 300M (embedding-gemma-300m-mediapipe-v1)
現在の値: maxSearchDistance = 0.4 / minRagSimilarity = 0.5
質問 14 件 / 文書 20 件
Recall@1 = ...
Recall@3 = ...
Recall@5 = ...
MRR = ...
nDCG@1 = ...
...
距離(関連あり): n=15 min=... p10=... median=... p90=... max=...
距離(関連なし): n=265 min=... p10=... median=... p90=... max=...
提案: maxSearchDistance = ... / minRagSimilarity = ... (precision ..., recall ..., F1 ...)
```

- 距離はコサイン距離(1 − コサイン類似度。ObjectBox の COSINE と同じ尺度で、小さいほど近い)。
- Recall@k: 上位 k 件に入った正解の割合。MRR: 最初の正解の順位の逆数の平均。nDCG@k: 正解が上位にあるほど高い。
- 「関連あり」と「関連なし」の距離の分布の重なりが小さいほど、しきい値で綺麗に分けられる。
- 「提案」は、距離が c 以下なら関連ありとしたときの F1 が最大になる c。`maxSearchDistance`(ハイブリッド検索)の目安で、
  `1 − c` が `minRagSimilarity`(チャット)の目安。

## しきい値への反映

1. モデルごとの値は `EmbeddingModelProfile`(`domain/provider/EmbeddingModelProfile.kt`)に書く。
   EmbeddingGemma 2 は `GEMMA_V2_EXPERIMENTAL` の `maxSearchDistance` / `minRagSimilarity` / `maxContentChars` が
   TODO(いまは v1 の値を仮に流用している)。
2. 評価データは 20 件・14 件と小さいので、提案値は目安として、実際のブックマークで検索した体感も合わせて決める。
   評価データを増やすときは `ja_bookmarks.json` に文書と質問を足す(質問の `relevant` には文書の ID を書く。
   質問の文を文書のタイトルや本文と同じにしない)。
3. 検索は現在の埋め込みモデルの版(`modelVersion`)のベクトルだけを比べる。モデルを替えた直後は、
   全ブックマークの再埋め込みが終わるまで(AI の設定画面の「検索インデックスを更新中」)、検索結果が少なくなる。

## 注意

- 入力は `EmbeddingInputBuilder` でアプリと同じ作り方にしている(タイトル + 本文。文字数の予算はモデルの `maxInputTokens` から決まる)。
- 対応する MediaPipe の TextEmbedder が EmbeddingGemma 2 を読めるかは未確認。読めない場合、計測は skip される。
