@echo off
rem SoloChat one-click build wrapper (Windows).
rem
rem NOTE: keep this file ASCII-only. cmd parses .bat as GBK on Chinese
rem Windows, and UTF-8 Chinese text swallows the newline and breaks parsing.
rem (QQ-agent hit exactly this; its launcher has the same warning.)
rem
rem Why a wrapper at all: this machine has four environment constraints
rem (JDK 21, workspace-local GRADLE_USER_HOME, ANDROID_PREFS_ROOT, and
rem sdk.dir in local.properties). Typing gradlew by hand hits one of them
rem every time. Details are in tools/build.ps1.
rem
rem Usage:
rem   build.bat            compile the debug APK
rem   build.bat test       run all unit tests

setlocal
set "ROOT=%~dp0"
pwsh -NoLogo -NoProfile -File "%ROOT%tools\build.ps1" %*
exit /b %ERRORLEVEL%
