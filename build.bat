@echo off
setlocal
set "GRADLE_VERSION=8.14.3"
set "INSTALL_ROOT=%LOCALAPPDATA%\GreetConsole"
set "GRADLE_HOME=%INSTALL_ROOT%\gradle-%GRADLE_VERSION%"

where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 was not found. Install Java 21 first:
  echo https://adoptium.net/temurin/releases/?version=21
  pause
  exit /b 1
)

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Downloading Gradle %GRADLE_VERSION%...
  if not exist "%INSTALL_ROOT%" mkdir "%INSTALL_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; $zip=Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath '%INSTALL_ROOT%' -Force; Remove-Item $zip -Force"
  if errorlevel 1 (
    echo Gradle download failed. Check your internet connection and try again.
    pause
    exit /b 1
  )
)

call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon clean build
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

echo.
echo Build complete: build\libs\greetconsole-*.jar
pause
