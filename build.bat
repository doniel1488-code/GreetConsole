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

where dotnet >nul 2>nul
if errorlevel 1 (
  echo .NET 8 SDK was not found. Install it first:
  echo https://dotnet.microsoft.com/download/dotnet/8.0
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

echo Publishing the self-contained C# desktop companion...
dotnet publish companion\GreetConsole\GreetConsole.csproj --configuration Release --runtime win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true --output companion\publish
if errorlevel 1 (
  echo C# companion build failed.
  pause
  exit /b 1
)
if not exist "companion\publish\GreetConsole.exe" (
  echo C# companion executable was not produced.
  pause
  exit /b 1
)
if not exist "src\main\resources\assets\greetconsole" mkdir "src\main\resources\assets\greetconsole"
copy /Y "companion\publish\GreetConsole.exe" "src\main\resources\assets\greetconsole\GreetConsole.exe" >nul
if errorlevel 1 (
  echo Could not copy the C# companion into the mod resources.
  pause
  exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" --no-daemon clean build
if errorlevel 1 (
  echo Fabric mod build failed.
  pause
  exit /b 1
)

echo.
echo Build complete: build\libs\greetconsole-1.1.0.jar
pause
