@echo off
REM ============================================================
REM  NexusUI - Build Script
REM  Compiles and packages the mod into a JAR file
REM ============================================================

echo === NexusUI Build ===
echo.

REM --- Configuration ---
set STARSECTOR_DIR=E:\Games\Starsector
set MOD_DIR=%~dp0
set SRC_DIR=%MOD_DIR%src
set OUT_DIR=%MOD_DIR%build\classes
set JAR_DIR=%MOD_DIR%jars
set JAR_NAME=NexusUI.jar

REM --- Classpath: Starsector API + LazyLib ---
set CP=%STARSECTOR_DIR%\starsector-core\starfarer.api.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\starfarer_obf.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\lwjgl.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\lwjgl_util.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\log4j-1.2.9.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\json.jar
set CP=%CP%;%STARSECTOR_DIR%\starsector-core\xstream-1.4.10.jar
set CP=%CP%;%STARSECTOR_DIR%\mods\LazyLib\jars\LazyLib.jar

REM --- Clean previous build ---
echo [1/4] Cleaning previous build...
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%"
if not exist "%JAR_DIR%" mkdir "%JAR_DIR%"

REM --- Find all Java source files ---
echo [2/4] Finding source files...
dir /s /b "%SRC_DIR%\com\nexusui\*.java" > "%MOD_DIR%build\sources.txt"
for /f %%A in ('type "%MOD_DIR%build\sources.txt" ^| find /c /v ""') do echo     Found %%A source files

REM --- Compile ---
echo [3/4] Compiling...
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -cp "%CP%" -d "%OUT_DIR%" @"%MOD_DIR%build\sources.txt" 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo *** COMPILATION FAILED ***
    echo Check errors above and fix them.
    pause
    exit /b 1
)

echo     Compilation successful!

REM --- Package JAR ---
echo [4/4] Packaging JAR...
cd /d "%OUT_DIR%"
jar cf "%JAR_DIR%\%JAR_NAME%" -C "%OUT_DIR%" .

echo.
echo === BUILD SUCCESSFUL ===
echo Output: %JAR_DIR%\%JAR_NAME%
echo.

REM --- Auto-install to Starsector mods folder ---
echo Installing to Starsector mods folder...
if not exist "%STARSECTOR_DIR%\mods\NexusUI" mkdir "%STARSECTOR_DIR%\mods\NexusUI"
xcopy /s /y /q "%MOD_DIR%jars" "%STARSECTOR_DIR%\mods\NexusUI\jars\" >nul
xcopy /s /y /q "%MOD_DIR%data" "%STARSECTOR_DIR%\mods\NexusUI\data\" >nul
copy /y "%MOD_DIR%mod_info.json" "%STARSECTOR_DIR%\mods\NexusUI\" >nul
echo Installed!
echo.
echo Enable "NexusUI" in the Starsector launcher.
pause
