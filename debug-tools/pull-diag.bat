@echo off
setlocal enabledelayedexpansion

:: Pull diagnostic frame captures from Android device
:: Usage: pull-diag.bat [output_dir]
:: Automatically detects release vs debug build on device.

set "OUTPUT_DIR=%~1"
if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=%USERPROFILE%\diag_dumps"

where adb >nul 2>&1
if errorlevel 1 (
    echo ERROR: adb not found in PATH
    exit /b 1
)

:: Auto-detect package: try debug first, then release
set "PKG="
for %%p in (com.rfandango.haku_x.debug com.rfandango.haku_x) do (
    if "!PKG!"=="" (
        set "TEST_PATH=/storage/emulated/0/Android/data/%%p/files/rt_dumps"
        adb shell ls "!TEST_PATH!" >nul 2>&1
        if not errorlevel 1 (
            set "PKG=%%p"
            set "DEVICE_BASE=!TEST_PATH!"
        )
    )
)

if "%PKG%"=="" (
    echo No xemu installation found on device.
    echo Checked: com.rfandango.haku_x.debug, com.rfandango.haku_x
    exit /b 1
)

echo Package: %PKG%
echo Searching for diagnostic sessions on device...
echo Path: %DEVICE_BASE%

:: List sessions
set "TMPLIST=%TEMP%\xemu_diag_sessions.txt"
adb shell ls -d "%DEVICE_BASE%/diag_session_*" > "%TMPLIST%" 2>nul

findstr /c:"diag_session_" "%TMPLIST%" >nul 2>&1
if errorlevel 1 (
    echo No diagnostic sessions found on device.
    echo Trigger a capture in-app: pause menu ^> Debug Capture
    del "%TMPLIST%" 2>nul
    exit /b 0
)

set "COUNT=0"
for /f "usebackq tokens=*" %%s in ("%TMPLIST%") do set /a COUNT+=1
echo Found %COUNT% session(s)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

for /f "usebackq tokens=*" %%s in ("%TMPLIST%") do (
    set "SESSION=%%s"
    for /f "tokens=*" %%t in ("!SESSION!") do set "SESSION=%%t"
    for %%n in ("!SESSION!") do set "SNAME=%%~nxn"

    echo.
    echo Pulling: !SNAME!
    set "DEST=%OUTPUT_DIR%\!SNAME!"
    if not exist "!DEST!" mkdir "!DEST!"

    :: Pull files one by one
    set "FLIST=%TEMP%\xemu_diag_files.txt"
    adb shell ls "!SESSION!" > "!FLIST!" 2>nul
    for /f "usebackq tokens=*" %%f in ("!FLIST!") do (
        set "FNAME=%%f"
        for /f "tokens=*" %%g in ("!FNAME!") do set "FNAME=%%g"
        if not "!FNAME!"=="" (
            adb pull "!SESSION!/!FNAME!" "!DEST!\!FNAME!"
        )
    )
    del "!FLIST!" 2>nul

    :: Clean from device
    adb shell run-as %PKG% rm -rf "!SESSION!" 2>nul
    adb shell rm -rf "!SESSION!" 2>nul
    echo Cleaned from device
)

del "%TMPLIST%" 2>nul

echo.
echo === Done ===
echo Saved to: %OUTPUT_DIR%
echo.
echo Next steps:
echo   1. Open diag-viewer.html in a browser
echo   2. Load diag_session.json from %OUTPUT_DIR%
echo   3. Click 'Copy for Claude' to paste into conversation
