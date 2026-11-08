# 保存したデータの暗号化(#100 の後半)

| データ | 守り方 |
| --- | --- |
| Room の DB(ブックマーク・タグ・会話・ノートなど。`echomark.db`) | **SQLCipher で暗号化**。鍵はこの端末だけ(Android Keystore) |
| ObjectBox(検索用の埋め込み) | 端末の暗号化(FBE)。ObjectBox のオープンソース版には保存時の暗号化が無い。中身はタイトル・要約・本文から作り直せる |
| 添付ファイル・オフラインコピー(`attachments/`) | 端末の暗号化(FBE)。暗号化すると、画像・PDF・音声・動画・文字認識・WebView・共有のたびに一時的な平文が要るため、今はしない |
| 設定(DataStore) | 端末の暗号化(FBE)。秘密の情報は入れない |
| API キー | Android Keystore の鍵で暗号化(前から) |
| アプリのバックアップ(JSON・zip) | 暗号化しない(利用者が選んだ場所に置き、別の端末で読み込むため) |

## 鍵

- ランダムな 32 バイトを 16 進の文字にしたものをパスフレーズにする(`DatabaseKeyStore`)。
- パスフレーズは Android Keystore の AES-GCM の鍵(`echomark_database_key`)で暗号化し、`noBackupFilesDir/database_key.bin` に置く。
  Keystore の鍵は端末の外へ出ない。利用者の認証(生体認証など)には結び付けない(裏で動く処理も DB を使うため)。
- パスフレーズはログ・例外の文言に出さない。SQL の文字列にも埋め込まない(バインドの引数で渡す)。

## 平文の DB の暗号化(初めて暗号化するとき)

Room が DB を最初に開くとき(裏のスレッド)に `EncryptingOpenHelperFactory` が `DatabaseEncryptionMigrator.prepare` を呼ぶ。

1. WAL を本体に書き戻し、`ATTACH ... KEY` と `sqlcipher_export` で `echomark-encrypted.db` に写す(DB の版も写す)。
2. 写したもののテーブルごとの行の数と版が、元と同じかを確かめる。違えば写したものを消し、今回は平文のまま開く(次の起動でやり直す)。
3. 平文を `echomark.db.plain-bak` に、写したものを `echomark.db` に名前を変える。平文の WAL・SHM は上書きして消す。
4. 暗号化した DB を開けたら、`.plain-bak` を 0 で上書きしてから消す(**移し終えたら平文の写しを残さない**)。
   開けなければ平文に戻す。
5. 3〜4 の間に止まったときは、次の起動で、暗号化した DB を開ければ `.plain-bak` を消し、開けなければ平文に戻して暗号化し直す。

上書きして消すのは、できる範囲の対策(フラッシュのストレージでは、書いた場所が元の場所と同じとは限らない)。
平文の写しがある時間をできるだけ短くし、端末の暗号化(FBE)と合わせて守る。

## 鍵が失われたとき

端末の初期化・Keystore の不具合などで鍵を復号できない(`UnrecoverableSecretException`)、または暗号化した DB が「DB でない」とされるときは、
DB を消して新しい鍵で作り直し、ObjectBox の埋め込みも消す。平文の写しは作らない。設定の「ロック」に、バックアップから戻すよう知らせる
(利用者が閉じるまで)。Keystore の一時的なエラーや、開けない理由が分からないときは、DB を消さずに失敗させる(次の起動でやり直す)。

## 全データを削除

DB の中身を消したあと、`.plain-bak`・`echomark-encrypted.db` が残っていれば消す(`DatabaseLeftovers`)。
暗号化した DB の空いた領域は暗号化されたまま。

## Android のバックアップ

DB は Android のクラウドのバックアップ・端末間の転送に入れない(`data_extraction_rules.xml`・`backup_rules.xml` の `domain="database"`)。
鍵が端末の外へ出ないため、別の端末へ移しても開けない。端末を移すときは、アプリのバックアップ(設定 → データ)を使う。

## テスト

- `DatabaseEncryptionMigratorTest`(JVM): 手順の分かれ道を、ファイルの形を真似た Fake の仕組み(`FakeEngine`・`FakeCipher`)で確かめる。
  本物の SQLCipher と Keystore は使わない。
- `DatabaseEncryptionInstrumentedTest`(端末): 本物の SQLCipher と Keystore で、平文の Room の DB の暗号化・Room で開く・違う鍵で読めない・
  ファイルに平文が見えない・鍵が失われたときに作り直す、を確かめる。

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unchunks.echomark.data.local.encryption.DatabaseEncryptionInstrumentedTest
```
