@echo off
rem Regenerate the Google Play store images (and promo video with -Video). See docs/store-listing.md.
rem Arguments are passed to tools/store-assets/make-store-assets.ps1 (e.g. store-assets.cmd -Video).
rem Calling through this file avoids the PowerShell execution policy blocking the script.
rem PSModulePath is cleared so that Windows PowerShell does not pick up PowerShell 7 modules
rem (inherited when this is run from pwsh), which fail to load in 5.1.
setlocal
set "PSModulePath="
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\store-assets\make-store-assets.ps1" %*
exit /b %ERRORLEVEL%
