<#
.SYNOPSIS
    リリース用に署名した AAB と APK を、コマンド一つで作る。

.DESCRIPTION
    鍵の場所・鍵の名前・パスワードを環境変数 ECHOMARK_KEYSTORE_FILE / ECHOMARK_KEY_ALIAS /
    ECHOMARK_KEYSTORE_PASSWORD / ECHOMARK_KEY_PASSWORD に入れて Gradle を呼ぶ
    (読むのは components/android/build-logic の ReleaseSigning)。
    パスワードはリポジトリにもコマンドラインにも置かない。環境変数に無ければ、その場で尋ねる。
    -RememberPassword を付けると、この Windows ユーザーだけが復号できる形(DPAPI)で
    %LOCALAPPDATA%\EchoMark\ に保存し、次からは尋ねない。

    リポジトリ直下に keystore.properties があるときは、そちらの値が優先される。

.EXAMPLE
    .\build-release.cmd
    AAB と APK の両方を作る。

.EXAMPLE
    .\build-release.cmd -Aab -RememberPassword
    AAB だけを作り、パスワードを保存する。

.EXAMPLE
    .\build-release.cmd -ForgetPassword
    保存したパスワードを消す。
#>
[CmdletBinding()]
param(
    # AAB だけ作る(Google Play に上げる用)
    [switch]$Aab,
    # APK だけ作る(端末に直接入れる用)
    [switch]$Apk,
    # 作る前に clean する
    [switch]$Clean,
    # 入力したパスワードを保存する(この Windows ユーザーだけが復号できる)
    [switch]$RememberPassword,
    # 保存したパスワードを消して終わる
    [switch]$ForgetPassword
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$DefaultKeystoreFile = 'C:\Users\progr\keystores\unchunks-studio-release'
$DefaultKeyAlias = 'release-key'
$PasswordFile = Join-Path $env:LOCALAPPDATA 'EchoMark\release-keystore-password.xml'
$RepoRoot = $PSScriptRoot

if ($ForgetPassword) {
    if (Test-Path $PasswordFile) {
        Remove-Item $PasswordFile -Force
        Write-Host "保存したパスワードを消しました: $PasswordFile"
    } else {
        Write-Host 'パスワードは保存されていません'
    }
    exit 0
}

function Set-DefaultEnv([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))) {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

function ConvertTo-PlainText([Security.SecureString]$Secure) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# --- ビルドに要る道具の場所(未設定のときだけ既定値を入れる) ---
$studioJbr = 'C:\Program Files\Android\Android Studio\jbr'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path $studioJbr)) {
    $env:JAVA_HOME = $studioJbr
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME が設定されていません。JDK 17 以上(Android Studio の jbr など)の場所を設定してください'
}
# components の composite build は自分の local.properties を持たないため、SDK の場所を環境変数でも渡す
$defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and (Test-Path $defaultSdk)) {
    $env:ANDROID_HOME = $defaultSdk
}

# --- 署名鍵 ---
$envNames = 'ECHOMARK_KEYSTORE_FILE', 'ECHOMARK_KEY_ALIAS', 'ECHOMARK_KEYSTORE_PASSWORD', 'ECHOMARK_KEY_PASSWORD'
# 呼び出し前から設定されていた環境変数は、終わったあとも残す
$preset = @{}
foreach ($name in $envNames) { $preset[$name] = [Environment]::GetEnvironmentVariable($name) }

try {
    Set-DefaultEnv 'ECHOMARK_KEYSTORE_FILE' $DefaultKeystoreFile
    Set-DefaultEnv 'ECHOMARK_KEY_ALIAS' $DefaultKeyAlias

    if (-not (Test-Path -LiteralPath $env:ECHOMARK_KEYSTORE_FILE -PathType Leaf)) {
        throw "署名鍵が見つかりません: $env:ECHOMARK_KEYSTORE_FILE"
    }

    if ([string]::IsNullOrEmpty($env:ECHOMARK_KEYSTORE_PASSWORD)) {
        $secure = $null
        if (Test-Path $PasswordFile) {
            try { $secure = Import-Clixml $PasswordFile } catch { $secure = $null }
        }
        if ($null -eq $secure) {
            $secure = Read-Host -AsSecureString "署名鍵のパスワード ($env:ECHOMARK_KEYSTORE_FILE)"
        }
        $env:ECHOMARK_KEYSTORE_PASSWORD = ConvertTo-PlainText $secure
    }
    # PKCS12 の鍵は、鍵のパスワードとストアのパスワードが同じ
    Set-DefaultEnv 'ECHOMARK_KEY_PASSWORD' $env:ECHOMARK_KEYSTORE_PASSWORD

    # 時間のかかるビルドの前に、パスワードと鍵の名前が合っているか確かめる
    # (パスワードは :env でコマンドラインに出さずに渡す)
    # (Windows PowerShell 5.1 は、Stop のまま外部コマンドの stderr を捨てると例外にするため、ここだけ Continue にする)
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    $ErrorActionPreference = 'Continue'
    & $keytool -list -keystore $env:ECHOMARK_KEYSTORE_FILE -storepass:env ECHOMARK_KEYSTORE_PASSWORD `
        -alias $env:ECHOMARK_KEY_ALIAS *> $null
    $keytoolExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($keytoolExit -ne 0) {
        if (Test-Path $PasswordFile) { Remove-Item $PasswordFile -Force }
        throw "署名鍵を開けません。パスワードか鍵の名前 ($env:ECHOMARK_KEY_ALIAS) が違います"
    }

    if ($RememberPassword) {
        New-Item -ItemType Directory -Force (Split-Path $PasswordFile) | Out-Null
        # ConvertTo-SecureString は使わない(PowerShell 7 から呼ばれた Windows PowerShell では、
        # 7 用の Microsoft.PowerShell.Security を読もうとして失敗することがある)
        $toSave = New-Object Security.SecureString
        foreach ($c in $env:ECHOMARK_KEYSTORE_PASSWORD.ToCharArray()) { $toSave.AppendChar($c) }
        $toSave.MakeReadOnly()
        $toSave | Export-Clixml $PasswordFile
        Write-Host "パスワードを保存しました(この Windows ユーザーだけが復号できます): $PasswordFile"
    }

    # --- ビルド ---
    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $both = -not ($Aab -or $Apk)
    if ($Aab -or $both) { $tasks += ':app:bundleRelease' }
    if ($Apk -or $both) { $tasks += ':app:assembleRelease' }

    Write-Host "> gradlew $($tasks -join ' ')"
    Push-Location $RepoRoot
    try {
        & (Join-Path $RepoRoot 'gradlew.bat') @tasks
        if ($LASTEXITCODE -ne 0) { throw "ビルドに失敗しました (終了コード $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    Write-Host ''
    Write-Host 'できたもの:'
    $outputs = @()
    if ($Aab -or $both) { $outputs += Join-Path $RepoRoot 'app\build\outputs\bundle\release\app-release.aab' }
    if ($Apk -or $both) { $outputs += Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk' }
    foreach ($path in $outputs) {
        if (Test-Path $path) {
            $size = '{0:N1} MB' -f ((Get-Item $path).Length / 1MB)
            Write-Host "  $path ($size)"
        } else {
            Write-Warning "見つかりません: $path"
        }
    }
} finally {
    # このスクリプトが入れた値(特にパスワード)を、呼び出し元のセッションに残さない
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $preset[$name], 'Process')
    }
}
