@echo off
setlocal
set "APP_HOME=%~dp0"
set "VERSION=9.6.0"
if exist "%APP_HOME%.tools\gradle-%VERSION%\bin\gradle.bat" (
  call "%APP_HOME%.tools\gradle-%VERSION%\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" (
  call "%GRADLE_HOME%\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
for %%A in (%*) do if "%%~A"=="--offline" (
  echo BeeKeep: Gradle %VERSION% is not installed locally and --offline was requested.
  echo Install Gradle %VERSION% or run without --offline on a network-enabled build host.
  exit /b 2
)
java -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
