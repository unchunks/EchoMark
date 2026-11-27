<#
.SYNOPSIS
  Google Play の掲載用の画像(と紹介動画)を、コマンド一つで作り直す(Windows 専用)。

.DESCRIPTION
  アプリを更新したら、リポジトリ直下で `.\store-assets.cmd` を実行するだけでよい。

  1. Roborazzi のテスト(app/src/test/.../screenshot/store/)で、本物の画面から画像(と動画のコマ)を描く。
     何を描くか(見出し・画面・フォント・色・アプリのデザインなど)は tools/store-assets/store-assets.json で決める
  2. 画像の透明度(アルファ)を落とし、fastlane/metadata/android/ja-JP/images/ に写す
     (Play Console はフィーチャー グラフィックにアルファ付きの PNG を受け付けない)
  3. -Video を付けたときは、動画のコマを H.264 の MP4(build/store-assets/echomark_promo.mp4)にする

  画像と MP4 の仕上げには、tools/store-assets/StoreAssetTool.cs を Windows に入っている .NET Framework の csc で
  ビルドして使う(ffmpeg などを別に入れなくてよい)。
  JAVA_HOME・ANDROID_HOME が無い・壊れているときは、Android Studio の JDK と SDK を探して使う。
  手順の全体と、Play Console に載せるときの注意は docs/store-listing.md。

.PARAMETER Video
  紹介動画も作る(コマを 1,000 枚ほど描くので、数十分かかる)

.PARAMETER SkipRender
  テストを動かさず、すでに描いた画像・コマから仕上げだけをする

.EXAMPLE
  .\store-assets.cmd
  .\store-assets.cmd -Video
  .\store-assets.cmd -Video -SkipRender
#>
param(
    [switch]$Video,
    [switch]$SkipRender
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$outputs = Join-Path $root 'app/build/outputs/roborazzi'
$work = Join-Path $root 'build/store-assets'
$images = Join-Path $root 'fastlane/metadata/android/ja-JP/images'
$tool = Join-Path $work 'StoreAssetTool.exe'

New-Item -ItemType Directory -Force $work | Out-Null

# ---- ビルドに要る道具の場所 ----

# JDK として使えるか(jvm.cfg の無い、壊れた・入れ替え途中の JDK を避ける)
function Test-Jdk([string]$path) {
    return $path -and (Test-Path (Join-Path $path 'bin/java.exe')) -and (Test-Path (Join-Path $path 'lib/jvm.cfg'))
}

if (-not (Test-Jdk $env:JAVA_HOME)) {
    # Android Studio(名前の違う版も含む)の jbr と、Gradle が取ってきた JDK から、新しいものを選ぶ
    $candidates = @()
    foreach ($base in @("$env:ProgramFiles\Android", "$env:LOCALAPPDATA\Programs")) {
        if (Test-Path $base) { $candidates += Get-ChildItem $base -Directory | ForEach-Object { Join-Path $_.FullName 'jbr' } }
    }
    $gradleJdks = Join-Path $env:USERPROFILE '.gradle/jdks'
    if (Test-Path $gradleJdks) { $candidates += Get-ChildItem $gradleJdks -Directory | ForEach-Object { $_.FullName } }
    $jdk = $candidates | Where-Object { Test-Jdk $_ } | Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK が見つかりません。JAVA_HOME に JDK 17 以上(Android Studio の jbr など)の場所を設定してください' }
    $env:JAVA_HOME = $jdk
}
Write-Host "JAVA_HOME: $env:JAVA_HOME"

# components の composite build は自分の local.properties を持たないため、SDK の場所を環境変数でも渡す
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    $sdk = $null
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\\\', '\' -replace '\\:', ':' }
    }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (-not (Test-Path $sdk)) { throw "Android SDK が見つかりません($sdk)。ANDROID_HOME を設定してください" }
    $env:ANDROID_HOME = $sdk
}

# ---- 1. 仕上げの道具のビルド ----
$csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path $csc)) { throw ".NET Framework の csc が見つかりません: $csc" }
& $csc -nologo -optimize -platform:x64 -r:System.Drawing.dll "-out:$tool" (Join-Path $PSScriptRoot 'StoreAssetTool.cs')
if ($LASTEXITCODE -ne 0) { throw 'StoreAssetTool のビルドに失敗しました' }

# ---- 2. テストで描く ----
if (-not $SkipRender) {
    Remove-Item -Recurse -Force (Join-Path $outputs 'store'), (Join-Path $outputs 'video') -ErrorAction SilentlyContinue
    if ($Video) { $env:ECHOMARK_STORE_VIDEO = '1' } else { Remove-Item Env:ECHOMARK_STORE_VIDEO -ErrorAction SilentlyContinue }
    Push-Location $root
    try {
        # echomark.storeAssetsRun に毎回ちがう値を渡し、コードが変わっていなくても必ず描き直させる(app/build.gradle.kts)
        & ./gradlew.bat :app:recordRoborazziDebug --tests 'com.unchunks.echomark.screenshot.store.*' "-Pechomark.storeAssetsRun=$([DateTime]::UtcNow.Ticks)"
        if ($LASTEXITCODE -ne 0) { throw 'テスト(画像の書き出し)に失敗しました。上のログと app/build/reports/tests/ を見てください' }
    } finally {
        Pop-Location
        Remove-Item Env:ECHOMARK_STORE_VIDEO -ErrorAction SilentlyContinue
    }
}

# ---- 3. 画像を fastlane の置き場所へ ----
function Copy-Flat([string]$src, [string]$dest) {
    & $tool flatten $src $dest
    if ($LASTEXITCODE -ne 0) { throw "変換に失敗しました: $src" }
}

$store = Join-Path $outputs 'store'
foreach ($name in 'icon.png', 'feature_graphic.png') {
    if (-not (Test-Path (Join-Path $store $name))) { throw "画像がありません: $store\$name(先にテストで描いてください)" }
}
foreach ($dir in 'phoneScreenshots', 'sevenInchScreenshots', 'tenInchScreenshots') {
    Remove-Item -Recurse -Force (Join-Path $images $dir) -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Join-Path $images $dir) | Out-Null
}

# アイコンは 32 ビット(アルファ付き)の PNG のまま使う
Copy-Item (Join-Path $store 'icon.png') (Join-Path $images 'icon.png') -Force
Copy-Flat (Join-Path $store 'feature_graphic.png') (Join-Path $images 'featureGraphic.png')

# ファイル名は設定(phone[].file・tablet[].file)のまま。Play ではファイル名の順に並ぶ
$phones = @(Get-ChildItem (Join-Path $store 'phone') -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object Name)
foreach ($png in $phones) { Copy-Flat $png.FullName (Join-Path $images "phoneScreenshots/$($png.Name)") }
# タブレットの画像は 7 インチと 10 インチで同じものを使う(どちらも 16:9 の 1920×1080)
$tablets = @(Get-ChildItem (Join-Path $store 'tablet') -Filter '*.png' -ErrorAction SilentlyContinue | Sort-Object Name)
foreach ($png in $tablets) {
    Copy-Flat $png.FullName (Join-Path $images "sevenInchScreenshots/$($png.Name)")
    Copy-Item (Join-Path $images "sevenInchScreenshots/$($png.Name)") (Join-Path $images "tenInchScreenshots/$($png.Name)") -Force
}

# Play Console の枚数の決まり(スマートフォンは 2〜8 枚、タブレットは 4〜8 枚)
if ($phones.Count -lt 2 -or $phones.Count -gt 8) { Write-Warning "スマートフォンの画像が $($phones.Count) 枚です(Play は 2〜8 枚)" }
if ($tablets.Count -gt 0 -and ($tablets.Count -lt 4 -or $tablets.Count -gt 8)) { Write-Warning "タブレットの画像が $($tablets.Count) 枚です(Play は 4〜8 枚)" }
Write-Host "画像: $images(スマートフォン $($phones.Count) 枚・タブレット $($tablets.Count) 枚)"

# ---- 4. 動画 ----
if ($Video) {
    $frames = Join-Path $outputs 'video'
    if (-not (Test-Path $frames)) { throw "動画のコマがありません: $frames(-Video を付けてテストで描いてください)" }
    $mp4 = Join-Path $work 'echomark_promo.mp4'
    # 1080p・30fps。画面の文字がつぶれないよう、YouTube の推奨より少し高めのビットレートにする
    & $tool encode $frames 30 12000000 $mp4
    if ($LASTEXITCODE -ne 0) { throw '動画の書き出しに失敗しました' }
    Write-Host "動画: $mp4"
}
