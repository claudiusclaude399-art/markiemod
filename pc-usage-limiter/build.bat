@echo off
REM ============================================================
REM  Build PCUsageLimiter.exe (run this ON WINDOWS)
REM  Requires Python 3.9+ installed and on PATH.
REM ============================================================
setlocal

echo.
echo === PC Usage Limiter - build script ===
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python was not found on PATH.
    echo Install Python 3 from https://www.python.org/downloads/
    echo and tick "Add Python to PATH" during install.
    pause
    exit /b 1
)

echo Installing build dependencies...
python -m pip install --upgrade pip
python -m pip install pyinstaller pystray Pillow keyboard
if errorlevel 1 (
    echo [ERROR] Dependency install failed.
    pause
    exit /b 1
)

echo.
echo Building single-file executable...
python -m PyInstaller ^
    --name PCUsageLimiter ^
    --onefile ^
    --noconsole ^
    --paths src ^
    --hidden-import pystray._win32 ^
    --collect-all pystray ^
    --collect-all PIL ^
    src\main.py

if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

echo.
echo === DONE ===
echo Your program is here:  dist\PCUsageLimiter.exe
echo.
echo Next: run  install.bat  to make it start with Windows,
echo or just double-click dist\PCUsageLimiter.exe to try it.
echo.
pause
endlocal
