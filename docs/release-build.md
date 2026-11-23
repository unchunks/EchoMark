# リリースビルド

リポジトリ直下で次を実行すると、リリース用に署名した AAB と APK ができる。

```powershell
.\build-release.cmd
```

- できるもの: `app\build\outputs\bundle\release\app-release.aab`(Google Play 用)と
  `app\build\outputs\apk\release\app-release.apk`(端末に直接入れる用)
- 署名鍵: `C:\Users\progr\keystores\unchunks-studio-release`(PKCS12、鍵の名前 `release-key`)。
  鍵のファイルはリポジトリに入れない
- パスワード: 実行のたびに尋ねる。ビルドの前に `keytool` で鍵を開けるか確かめるので、間違えてもすぐ分かる

## オプション

| オプション | 意味 |
|---|---|
| `-Aab` | AAB だけ作る |
| `-Apk` | APK だけ作る |
| `-Clean` | 先に clean する |
| `-RememberPassword` | 入力したパスワードを `%LOCALAPPDATA%\EchoMark\release-keystore-password.xml` に保存し、次から尋ねない。DPAPI で暗号化するので、この PC のこの Windows ユーザーだけが復号できる |
| `-ForgetPassword` | 保存したパスワードを消す |

`build-release.cmd` は `build-release.ps1` を実行ポリシーに止められずに呼ぶための入口。PowerShell から `.\build-release.ps1` を直接呼んでもよい。

## 仕組み

署名の設定を読むのは components の `build-logic` の `ReleaseSigning`。スクリプトは次の環境変数を入れて Gradle を呼ぶだけで、
終わったら元に戻す(パスワードを呼び出し元のセッションに残さない)。

| 環境変数 | 既定値 |
|---|---|
| `ECHOMARK_KEYSTORE_FILE` | `C:\Users\progr\keystores\unchunks-studio-release` |
| `ECHOMARK_KEY_ALIAS` | `release-key` |
| `ECHOMARK_KEYSTORE_PASSWORD` | 保存したパスワードか、その場で入力した値 |
| `ECHOMARK_KEY_PASSWORD` | `ECHOMARK_KEYSTORE_PASSWORD` と同じ(PKCS12 は同じパスワード) |

前もって環境変数を入れておけば、その値が使われる(別の鍵で署名したいときなど)。
リポジトリ直下に `keystore.properties` があると、そちらが環境変数より優先される。

`JAVA_HOME` と `ANDROID_HOME` が無ければ、Android Studio の `jbr` と `%LOCALAPPDATA%\Android\Sdk` を使う。

鍵が設定されていないまま `gradlew assembleRelease` を直接呼ぶと、debug の鍵で仮に署名される(端末での確認用。配布には使えない)。
