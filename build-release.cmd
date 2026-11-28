@echo off
rem Build signed release AAB + APK. Arguments are passed to build-release.ps1 (e.g. build-release.cmd -Aab).
rem Calling through this file avoids the PowerShell execution policy blocking the script.
rem PSModulePath is cleared so that Windows PowerShell does not pick up PowerShell 7 modules
rem (inherited when this is run from pwsh), which fail to load in 5.1.
setlocal
set "PSModulePath="
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
exit /b %ERRORLEVEL%
