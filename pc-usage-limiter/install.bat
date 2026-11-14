@echo off
REM ============================================================
REM  Install PCUsageLimiter so it starts with Windows.
REM  Copies the exe to %LOCALAPPDATA% and adds a startup entry
REM  (per-user, so NO admin rights are needed).
REM ============================================================
setlocal

set "EXE=dist\PCUsageLimiter.exe"
if not exist "%EXE%" (
    echo [ERROR] %EXE% not found. Run build.bat first.
    pause
    exit /b 1
)

set "DEST=%LOCALAPPDATA%\PCUsageLimiter"
if not exist "%DEST%" mkdir "%DEST%"
copy /Y "%EXE%" "%DEST%\PCUsageLimiter.exe" >nul

REM Register under the current-user Run key (the app also self-registers,
REM but this guarantees it even before first launch).
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" ^
    /v PCUsageLimiter /t REG_SZ /d "\"%DEST%\PCUsageLimiter.exe\"" /f >nul

echo.
echo Installed to: %DEST%\PCUsageLimiter.exe
echo It will now start automatically when you log in.
echo Starting it now...
start "" "%DEST%\PCUsageLimiter.exe"
echo.
echo On first launch you will set your codes. WRITE DOWN the recovery code!
pause
endlocal
