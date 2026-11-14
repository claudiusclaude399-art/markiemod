@echo off
REM ============================================================
REM  ONE-CLICK SETUP for PC Usage Limiter.
REM  Double-click this file. It will:
REM    1) install Python automatically if it's missing,
REM    2) build PCUsageLimiter.exe,
REM    3) install it to start with Windows and launch it.
REM  No admin rights required.
REM ============================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

echo.
echo ===========================================
echo   PC Usage Limiter - one-click setup
echo ===========================================
echo.

REM ---- 1. make sure we have a working Python ----
set "PY="
where py  >nul 2>nul && set "PY=py -3"
if not defined PY ( where python >nul 2>nul && set "PY=python" )

if not defined PY (
    echo Python was not found. Installing it automatically...
    where winget >nul 2>nul
    if !errorlevel! equ 0 (
        winget install -e --id Python.Python.3.12 --silent ^
            --accept-package-agreements --accept-source-agreements
    ) else (
        echo winget is unavailable - downloading the official Python installer...
        set "PYINST=%TEMP%\python-setup.exe"
        powershell -NoProfile -Command ^
          "try { Invoke-WebRequest 'https://www.python.org/ftp/python/3.12.7/python-3.12.7-amd64.exe' -OutFile '%TEMP%\python-setup.exe' } catch { exit 1 }"
        if exist "!PYINST!" (
            "!PYINST!" /quiet InstallAllUsers=0 PrependPath=1 Include_tcltk=1 Include_launcher=1
        )
    )
    REM Re-detect after install (PATH in this window may be stale, so probe both).
    set "PY="
    where py  >nul 2>nul && set "PY=py -3"
    if not defined PY ( where python >nul 2>nul && set "PY=python" )
)

if not defined PY (
    echo.
    echo [!] Python was installed but this window can't see it yet.
    echo     Please CLOSE this window and double-click SETUP.bat again.
    echo.
    pause
    exit /b 1
)

echo Using Python: %PY%
echo.

REM ---- 2. install build dependencies ----
echo Installing dependencies...
%PY% -m pip install --upgrade pip >nul
%PY% -m pip install pyinstaller pystray Pillow keyboard
if errorlevel 1 (
    echo [ERROR] Could not install dependencies. Check your internet connection.
    pause
    exit /b 1
)

REM ---- 3. build the exe ----
echo.
echo Building PCUsageLimiter.exe ...
%PY% -m PyInstaller --name PCUsageLimiter --onefile --noconsole ^
    --paths src --hidden-import pystray._win32 ^
    --collect-all pystray --collect-all PIL src\main.py
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

REM ---- 4. install + launch ----
set "DEST=%LOCALAPPDATA%\PCUsageLimiter"
if not exist "%DEST%" mkdir "%DEST%"
copy /Y "dist\PCUsageLimiter.exe" "%DEST%\PCUsageLimiter.exe" >nul
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" ^
    /v PCUsageLimiter /t REG_SZ /d "\"%DEST%\PCUsageLimiter.exe\"" /f >nul

echo.
echo ===========================================
echo   DONE - it will now start with Windows.
echo   Launching setup wizard...
echo ===========================================
start "" "%DEST%\PCUsageLimiter.exe"
echo.
echo Remember to WRITE DOWN the recovery code shown in the wizard.
echo.
pause
endlocal
