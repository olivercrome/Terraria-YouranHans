@echo off
chcp 65001 >nul
title YouranEditor - Build APK
setlocal
cd /d "%~dp0apps\editor"

rem ============================================================
rem  YouranEditor local build (Windows)
rem  Usage: double-click, or run:  构建APK.bat release
rem  It locates JDK 17 + Android SDK, then runs the gradle wrapper.
rem  Edit the two paths below if your installation differs.
rem ============================================================
set "JDK17=D:\android-sdk\jdk17"
set "SDKROOT=D:\android-sdk"

if not exist "%JDK17%\bin\javac.exe" (
  echo [ERROR] JDK 17 not found at: %JDK17%
  echo         Edit this .bat and set JDK17 to your JDK 17 folder.
  echo.
  pause
  exit /b 1
)
if not exist "%SDKROOT%" (
  echo [ERROR] Android SDK not found at: %SDKROOT%
  echo         Edit this .bat and set SDKROOT to your Android SDK folder.
  echo.
  pause
  exit /b 1
)
if not exist "%SDKROOT%\platforms\android-34" (
  echo [WARN] platforms\android-34 is missing - assemble may fail.
  echo        Install it with:
  echo        "%SDKROOT%\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34"
  echo.
)

set "JAVA_HOME=%JDK17%"
set "ANDROID_HOME=%SDKROOT%"
set "ANDROID_SDK_ROOT=%SDKROOT%"

rem local.properties is git-ignored; keep the SDK path machine-local.
rem (use forward slashes: java.util.Properties treats "\" as an escape char)
if not exist "local.properties" (
  > "local.properties" echo sdk.dir=%SDKROOT:\=/%
  echo [INFO] created local.properties -^> sdk.dir=%SDKROOT:\=/%
)

set "TASK=assembleDebug"
if /i "%~1"=="release" set "TASK=assembleRelease"
if /i "%~1"=="clean"   set "TASK=clean assembleDebug"

echo.
echo ==== YouranEditor build ====
echo   JDK          : %JAVA_HOME%
echo   Android SDK  : %ANDROID_HOME%
echo   Gradle task  : %TASK%
echo   First run downloads Gradle + dependencies, please be patient.
echo ============================
echo.

call gradlew.bat %TASK%
if errorlevel 1 (
  echo.
  echo [FAILED] Build failed - see the messages above.
  echo.
  pause
  exit /b 1
)

echo.
echo [OK] Build finished. APK output:
if /i "%TASK%"=="assembleRelease" (
  echo   %CD%\app\build\outputs\apk\release\
) else (
  echo   %CD%\app\build\outputs\apk\debug\app-debug.apk
)
echo.
pause
