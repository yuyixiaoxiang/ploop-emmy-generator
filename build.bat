@echo off
echo ========================================
echo EmmyLua Annotation Generator Build Script
echo ========================================
echo.

REM 检查是否有gradle wrapper
if exist gradlew.bat (
    echo Using Gradle Wrapper...
    call gradlew.bat buildPlugin
) else (
    echo Gradle Wrapper not found. Downloading...
    
    REM 尝试使用全局gradle
    where gradle >nul 2>nul
    if %ERRORLEVEL% EQU 0 (
        echo Using global Gradle...
        gradle wrapper
        call gradlew.bat buildPlugin
    ) else (
        echo.
        echo ERROR: Gradle not found!
        echo.
        echo Please install Gradle first:
        echo 1. Download from https://gradle.org/releases/
        echo 2. Or use SDKMAN: sdk install gradle
        echo 3. Or use Chocolatey: choco install gradle
        echo.
        echo After installing, run this script again.
        pause
        exit /b 1
    )
)

echo.
echo ========================================
echo Build completed!
echo.
echo Plugin location:
echo build\distributions\emmylua-annotation-generator-1.0.0.zip
echo.
echo To install in Rider:
echo 1. Open Rider
echo 2. File - Settings - Plugins
echo 3. Gear icon - Install Plugin from Disk...
echo 4. Select the zip file above
echo 5. Restart Rider
echo ========================================
pause
