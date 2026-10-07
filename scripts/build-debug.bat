@echo off
setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0doctor.ps1"
if errorlevel 1 exit /b 1
call "%~dp0..\gradlew.bat" :app:assembleDebug
if errorlevel 1 exit /b %ERRORLEVEL%
if exist "app\build\outputs\apk\debug\app-debug.apk" echo APK: app\build\outputs\apk\debug\app-debug.apk
endlocal
