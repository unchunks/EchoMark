# スクリーンショットテスト(Roborazzi)

エミュレータなしで Compose の見た目を確認するための仕組み。Robolectric(JVM)上で Composable を描画し、PNG を書き出す。

## 実行コマンド

```sh
# PNG を記録する(全スクショテスト)
./gradlew :app:recordRoborazziDebug

# 一部だけ記録する
./gradlew :app:recordRoborazziDebug --tests "com.unchunks.echomark.screenshot.ComponentsScreenshotTest"

# 直前に記録した PNG と比較する(差分画像 *_compare.png を出力。失敗はしない)
./gradlew :app:compareRoborazziDebug

# 直前に記録した PNG と比較し、差分があれば失敗させる
./gradlew :app:verifyRoborazziDebug
```

- 出力先: `app/build/outputs/roborazzi/<name>_light.png` / `<name>_dark.png`
- 通常の `./gradlew :app:testDebugUnitTest` ではスクショは撮らず、比較もしない(テストが描画エラーなく通るかだけ確認される)。
- PNG はリポジトリにコミットしない(`build/` 配下のため gitignore 済み)。フォントや OS による描画差があり、並行作業で差分が衝突しやすいため。比較したいときは「変更前に record → 変更後に compare」とローカルで使う。

## テストの書き方

`app/src/test/java/com/unchunks/echomark/screenshot/` に置く。

```kotlin
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FooScreenshotTest {
    @Test
    fun foo() = captureLightDark("foo") {
        FooContent(state = FooUiState(...), onClick = {})
    }
}
```

- `captureLightDark(name, widthDp = 400) { ... }`(`ScreenshotSupport.kt`)が、ブランド配色のライト/ダーク両方で描画して保存する。
- Hilt・ViewModel は使えない。画面は「UI 状態とコールバックを受け取るだけの Composable」(例: `XxxScreen(viewModel)` の中身を `XxxContent(uiState, onXxx)` に分ける)を用意して、それを撮る。
- 相対日時などは `nowMillis` を固定値で渡し、実行日によって画像が変わらないようにする。
- ネットワーク画像(Coil)は読み込まれない。プレースホルダーの見た目が撮られる。

## 設定

- `app/src/test/resources/robolectric.properties`: SDK 36 固定、素の `Application`(Hilt を起動しない)、既定の画面サイズ `w400dp-h1600dp-xhdpi`。部品は中身の大きさで切り出される。画面全体を撮るときはテストクラスに `@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)` などを付ける。
- `gradle.properties` の `roborazzi.record.filePathStrategy=relativePathFromRoborazziContextOutputDirectory` で、`captureRoboImage("name.png")` の保存先を出力ディレクトリ基準にしている。
- 初回実行時に Robolectric が Android の実行環境 jar(android-all)を Maven Central から取得する。
