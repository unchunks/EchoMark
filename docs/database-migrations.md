# DB のスキーマ変更とマイグレーション

ブックマーク・タグ・会話は Room(SQLite)に、検索用の埋め込みベクトルは ObjectBox に保存している。
列やテーブルを足すたびに、利用者の端末にある DB を**データを消さずに**新しい形へ上げる必要がある。
ここではその方針と、スキーマを変えるときの手順をまとめる。

## 構成

| 場所 | 役割 |
| --- | --- |
| `app/src/main/java/.../data/local/AppDatabase.kt` | エンティティの一覧と、スキーマの版(`AppDatabase.VERSION`) |
| `app/src/main/java/.../data/local/Migrations.kt` | 正式なマイグレーション(`MIGRATION_n_m`)と `ALL_MIGRATIONS`、対応する版の範囲 |
| `app/src/main/java/.../di/DatabaseModule.kt` | `applyMigrationPolicy`。マイグレーションと、作り直す(破壊的な)経路の設定 |
| `app/schemas/com.unchunks.echomark.data.local.AppDatabase/<版>.json` | 版ごとのスキーマ。ビルド(KSP)が書き出す。**コミットする** |
| `app/src/test/java/.../data/local/MigrationTest.kt` | マイグレーションごとに、既存の行の値がどうなるかのテスト |
| `app/src/test/java/.../data/local/MigrationPathTest.kt` | 全部の版から現在の版まで上げられ、データが残るかのテスト(版を上げても書き足し不要) |
| `app/src/test/java/.../data/local/DestructiveMigrationTest.kt` | 作り直す経路が、決めた場合にだけ動くかのテスト |
| `app/objectbox-models/default.json` | ObjectBox のモデル(UID)。ビルドが更新する。**コミットする** |
| `app/src/test/java/.../data/local/objectbox/ObjectBoxModelTest.kt` | ObjectBox の UID が失われていないかのテスト |

テストはどれも Robolectric(JVM)で動き、`./gradlew :app:testDebugUnitTest` に含まれる。
スキーマ JSON は `app/build.gradle.kts` の設定で debug の assets に入り、`MigrationTestHelper` がそこから読む。

## 版の履歴と、データを作り直す経路

| 版 | 扱い |
| --- | --- |
| 1〜5 | 開発初期(2026-08〜09、公開前の開発用ビルドだけ)。正式なマイグレーションは無く、**作り直す**。v5 はスキーマ JSON も残っていない(同じ日に v6 へ上げたため) |
| 6〜 | 正式なマイグレーションでデータを保つ(`OLDEST_MIGRATABLE_VERSION = 6`) |
| 11 | 最初の公開版(v1.0 プレリリース、2026-10-11)。利用者の端末にはこれ以降の DB しか無い(`FIRST_RELEASED_VERSION`) |

データを作り直す経路は次の 2 つだけにしている。どちらのときも ObjectBox の埋め込みも消す
(ブックマークの ID が 1 から振り直され、古いベクトルが別のブックマークに紐付くのを防ぐ)。

- **v1〜5 からの更新**(`DESTRUCTIVE_MIGRATION_FROM_VERSIONS`): 公開版には存在しない版。ここに新しい版を足してはいけない。
- **debug ビルドでのダウングレード**: ブランチを切り替えて版の低いアプリを入れたとき。開発中に開けなくなるのを避けるため。
  release ではダウングレードでも作り直さず、開けずに失敗させる(データは残り、新しいアプリを入れ直せば戻る)。
  versionCode を上げていない間は、古い APK を上書きで入れられてしまうため。

それ以外(マイグレーションが足りない、版を上げ忘れた)は Room が開けずに失敗する。**黙ってデータを消すことはない。**

## スキーマを変える手順

1. エンティティを変える(`data/local/entity/`)。テーブルを足すなら `AppDatabase` の `entities` にも足す。
2. `AppDatabase.VERSION` を 1 上げる。
3. `Migrations.kt` に `MIGRATION_<旧>_<新>` を書き、`ALL_MIGRATIONS` の末尾に足す。
   KDoc に「何を変えたか」と「既存の行がどうなるか」を書く(既存のマイグレーションの書き方に合わせる)。
4. ビルドする(`./gradlew :app:assembleDebug` など)。`app/schemas/.../<新>.json` が書き出されるので、**コミットに含める**。
5. `MigrationTest` に、旧版の DB に行を入れて `runMigrationsAndValidate` で上げ、既存の行の値を確かめるテストを足す。
   既定値の入る列や、マイグレーションの中で埋める値(`UPDATE`)は必ず確かめる。
6. 利用者のデータの列なら、バックアップ(`data/backup/BackupJson.kt`)にも足す。`CURRENT_VERSION` を上げ、
   古い版のバックアップを読むときの値を決める(DB のマイグレーションと同じ扱いにする)。足さないと、書き出し・読み込みでその列が消える。
7. テストを通す。

   ```sh
   ./gradlew :app:testDebugUnitTest --tests "com.unchunks.echomark.data.local.*"
   ```

   `MigrationPathTest` は、対応する全部の版(v6〜)の DB に全部の列に値の入った行を入れ、アプリと同じ方針で現在の版まで上げ、
   行と値が残っているか・最新のスキーマ JSON と一致するかを確かめる。手順 2〜4 のどれかが抜けていると失敗する。

   版を上げ下げしたり、エンティティの変更を戻したりしたあとは、KSP の差分ビルドが古いスキーマ JSON を
   `app/schemas/` に書き戻すことがある。`git diff app/schemas` で、変えていない版の JSON が変わっていないか確かめる
   (変わっていたら `git checkout` で戻し、`./gradlew :app:clean` してからビルドし直す)。

## データを保つための決まり

- **公開した版のマイグレーションとスキーマ JSON は書き換えない。** 直すときは次の版のマイグレーションで直す。
- `fallbackToDestructiveMigration*` を足さない。`DESTRUCTIVE_MIGRATION_FROM_VERSIONS` に版を足さない。`OLDEST_MIGRATABLE_VERSION` を上げない。
- 列の追加は `ALTER TABLE ... ADD COLUMN`。
  - `NOT NULL` の列には `DEFAULT` を付ける(既存の行に入る値)。エンティティに `@ColumnInfo(defaultValue = ...)` を宣言するなら同じ値にする
    (Room は宣言した既定値だけを検証する。宣言しない列に `DEFAULT` を付けて足すのは問題ない。`MIGRATION_8_9` を参照)。
  - 既存の行に、既定値ではない値を入れるなら、同じマイグレーションの中で `UPDATE` する(`MIGRATION_10_11` を参照)。
- テーブルの追加は、書き出された `<新>.json` の `createSql`(`${TABLE_NAME}` を置き換える)と `indices` の SQL をそのまま使う。
  手で書くと型・制約が食い違い、Room の検証で失敗する。
- 列の削除・名前や型の変更・制約(`NOT NULL`・外部キー)の変更は、SQLite の `ALTER TABLE` では素直にできない。
  新しいテーブルを作る → `INSERT INTO 新 SELECT ... FROM 旧` で写す → 旧を `DROP` → 新を `RENAME` → インデックスを作り直す、
  の順で行い、行が全部写ることをテストで確かめる。
- テーブル・列を消す、既存の値を書き換えるのは、利用者のデータが消えるので原則しない。どうしても必要なら、理由をマイグレーションの
  KDoc に書き、`MigrationPathTest` の `INTENTIONAL_DATA_CHANGES` に載せる(載せないとテストが失敗する)。
- `@AutoMigration` も使えるが、名前の変更・削除には `AutoMigrationSpec` が要り、何が起きるかが読みにくいため、手書きの `Migration` を基本にする。
  使うときも `MigrationTest` にテストを足す(`MigrationPathTest` は Room が自動で足すため、そのまま対象になる)。

### 並行して進む PR と版番号

複数の PR がそれぞれ版を上げると、同じ版番号を別の形に使ってしまう。そのまま両方が develop に入ると、
先に入った方の版の DB を持つ端末では、後の方の DB を開けなくなる(Room の検証で失敗する)。

- スキーマを変える PR は、1 つずつ develop に入れる。
- 後から入れる PR は develop を取り込み、版を上げ直す(`AppDatabase.VERSION`・マイグレーション名・`ALL_MIGRATIONS`)。
  自分の `<版>.json` は消してビルドし直し、書き出された JSON をコミットする。develop 側の JSON は書き換えない。

### CI で見るとよいこと

- `./gradlew :app:testDebugUnitTest`(上のテストが含まれる)。
- ビルドの後に `git diff --exit-code app/schemas app/objectbox-models` で、書き出されたスキーマ・モデルのコミット漏れが無いこと。

## ObjectBox(埋め込み)

埋め込みは、ブックマークのタイトル・要約・本文から作る**派生データ**で、消えても `ReembedAllWorker`(起動時に積む)が作り直す。
ただし全件の作り直しには時間と電池がかかり、その間は意味の検索・関連ブックマークが弱くなるため、むやみに消さない。

ObjectBox は Room のようなマイグレーションを持たず、`app/objectbox-models/default.json` の UID でエンティティ・プロパティを見分ける。

- **`default.json` を消さない・作り直さない。** UID が振り直され、既存の端末で DB を開けなくなる。コミットし、競合したら
  ObjectBox のドキュメントの手順で解く(片方の UID を残し、`lastPropertyId` などを大きい方にそろえる)。
- プロパティ・エンティティの名前を変えるときは `@Uid` で元の UID を指定する。指定しないと別のものとして扱われ、値が消える。
- プロパティの型を変えるには新しい UID が要り、そのプロパティの値は消える(埋め込みなら作り直しになる)。
- プロパティの追加は安全(既存のオブジェクトは既定値になる)。足したら `ObjectBoxModelTest` の `KNOWN_UIDS` にも足す。
- `vector` の HNSW 索引は 768 次元で固定している。次元の違う埋め込みモデルを入れるときは、索引の次元も変え、全件を作り直す
  (次元の合わないベクトルは索引で正しく扱われない)。
- Room の DB を作り直したときは、ObjectBox も全件消す(`DatabaseModule` の `onDestructiveMigration`)。
