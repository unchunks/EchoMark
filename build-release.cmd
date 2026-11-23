@echo off
rem Build signed release AAB + APK. Arguments are passed to build-release.ps1 (e.g. build-release.cmd -Aab).
rem Calling through this file avoids the PowerShell execution policy blocking the script.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
exit /b %ERRORLEVEL%
