@echo off
REM ============================================================
REM  Remove PCUsageLimiter completely (the escape hatch).
REM  Stops it, removes startup entry, restores Task Manager,
REM  and deletes the installed copy. Settings/usage in
REM  %APPDATA%\PCUsageLimiter are left in place unless you
REM  choose to delete them below.
REM ============================================================
setlocal

echo Stopping PCUsageLimiter...
taskkill /IM PCUsageLimiter.exe /F >nul 2>nul

echo Removing startup entry...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v PCUsageLimiter /f >nul 2>nul

echo Restoring Task Manager (in case it was blocked)...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Policies\System" /v DisableTaskMgr /f >nul 2>nul

echo Deleting installed program...
rmdir /S /Q "%LOCALAPPDATA%\PCUsageLimiter" >nul 2>nul

echo.
set /p DELDATA="Also delete your settings and usage data? (y/N): "
if /I "%DELDATA%"=="y" (
    rmdir /S /Q "%APPDATA%\PCUsageLimiter" >nul 2>nul
    echo Settings deleted.
) else (
    echo Settings kept at %APPDATA%\PCUsageLimiter
)

echo.
echo PCUsageLimiter has been removed.
pause
endlocal
